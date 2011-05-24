/**
 * Copyright (c) 2002-2011 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.ha;

import static org.neo4j.com.Protocol.EMPTY_SERIALIZER;
import static org.neo4j.com.Protocol.INTEGER_DESERIALIZER;
import static org.neo4j.com.Protocol.INTEGER_SERIALIZER;
import static org.neo4j.com.Protocol.LONG_SERIALIZER;
import static org.neo4j.com.Protocol.VOID_DESERIALIZER;
import static org.neo4j.com.Protocol.VOID_SERIALIZER;
import static org.neo4j.com.Protocol.readString;
import static org.neo4j.com.Protocol.writeString;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

import org.jboss.netty.buffer.ChannelBuffer;
import org.neo4j.com.BlockLogBuffer;
import org.neo4j.com.BlockLogReader;
import org.neo4j.com.Client;
import org.neo4j.com.Deserializer;
import org.neo4j.com.MasterCaller;
import org.neo4j.com.ObjectSerializer;
import org.neo4j.com.Protocol;
import org.neo4j.com.RequestType;
import org.neo4j.com.Response;
import org.neo4j.com.Serializer;
import org.neo4j.com.SlaveContext;
import org.neo4j.com.StoreWriter;
import org.neo4j.com.ToNetworkStoreWriter;
import org.neo4j.com.TxExtractor;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.helpers.Pair;
import org.neo4j.kernel.IdType;
import org.neo4j.kernel.ha.zookeeper.Machine;
import org.neo4j.kernel.impl.nioneo.store.IdRange;

/**
 * The {@link Master} a slave should use to communicate with its master. It
 * serializes requests and sends them to the master, more specifically
 * {@link MasterServer} (which delegates to {@link MasterImpl}
 * on the master side.
 */
public class MasterClient extends Client<Master> implements Master
{
    static final ObjectSerializer<LockResult> LOCK_SERIALIZER = new ObjectSerializer<LockResult>()
    {
        public void write( LockResult responseObject, ChannelBuffer result ) throws IOException
        {
            result.writeByte( responseObject.getStatus().ordinal() );
            if ( responseObject.getStatus().hasMessage() )
            {
                writeString( result, responseObject.getDeadlockMessage() );
            }
        }
    };
    protected static final Deserializer<LockResult> LOCK_RESULT_DESERIALIZER = new Deserializer<LockResult>()
    {
        public LockResult read( ChannelBuffer buffer, ByteBuffer temporaryBuffer ) throws IOException
        {
            LockStatus status = LockStatus.values()[buffer.readByte()];
            return status.hasMessage() ? new LockResult( readString( buffer ) ) : new LockResult(
                    status );
        }
    };

    public MasterClient( String hostNameOrIp, int port, GraphDatabaseService graphDb )
    {
        super( hostNameOrIp, port, graphDb );
    }

    public MasterClient( Machine machine, GraphDatabaseService graphDb )
    {
        this( machine.getServer().first(), machine.getServer().other(), graphDb );
    }
    
    @Override
    protected boolean shouldCheckStoreId( RequestType<Master> type )
    {
        return type != HaRequestType.COPY_STORE;
    }
    
    public Response<IdAllocation> allocateIds( final IdType idType )
    {
        return sendRequest( HaRequestType.ALLOCATE_IDS, SlaveContext.EMPTY, new Serializer()
        {
            public void write( ChannelBuffer buffer, ByteBuffer readBuffer ) throws IOException
            {
                buffer.writeByte( idType.ordinal() );
            }
        }, new Deserializer<IdAllocation>()
        {
            public IdAllocation read( ChannelBuffer buffer, ByteBuffer temporaryBuffer ) throws IOException
            {
                return readIdAllocation( buffer );
            }
        } );
    }

    public Response<Integer> createRelationshipType( SlaveContext context, final String name )
    {
        return sendRequest( HaRequestType.CREATE_RELATIONSHIP_TYPE, context, new Serializer()
        {
            public void write( ChannelBuffer buffer, ByteBuffer readBuffer ) throws IOException
            {
                writeString( buffer, name );
            }
        }, new Deserializer<Integer>()
        {
            @SuppressWarnings( "boxing" )
            public Integer read( ChannelBuffer buffer, ByteBuffer temporaryBuffer ) throws IOException
            {
                return buffer.readInt();
            }
        } );
    }

    public Response<LockResult> acquireNodeWriteLock( SlaveContext context, long... nodes )
    {
        return sendRequest( HaRequestType.ACQUIRE_NODE_WRITE_LOCK, context,
                new AcquireLockSerializer( nodes ), LOCK_RESULT_DESERIALIZER );
    }

    public Response<LockResult> acquireNodeReadLock( SlaveContext context, long... nodes )
    {
        return sendRequest( HaRequestType.ACQUIRE_NODE_READ_LOCK, context,
                new AcquireLockSerializer( nodes ), LOCK_RESULT_DESERIALIZER );
    }

    public Response<LockResult> acquireRelationshipWriteLock( SlaveContext context,
            long... relationships )
    {
        return sendRequest( HaRequestType.ACQUIRE_RELATIONSHIP_WRITE_LOCK, context,
                new AcquireLockSerializer( relationships ), LOCK_RESULT_DESERIALIZER );
    }

    public Response<LockResult> acquireRelationshipReadLock( SlaveContext context,
            long... relationships )
    {
        return sendRequest( HaRequestType.ACQUIRE_RELATIONSHIP_READ_LOCK, context,
                new AcquireLockSerializer( relationships ), LOCK_RESULT_DESERIALIZER );
    }

    public Response<Long> commitSingleResourceTransaction( SlaveContext context,
            final String resource, final TxExtractor txGetter )
    {
        return sendRequest( HaRequestType.COMMIT, context, new Serializer()
        {
            public void write( ChannelBuffer buffer, ByteBuffer readBuffer ) throws IOException
            {
                writeString( buffer, resource );
                BlockLogBuffer blockLogBuffer = new BlockLogBuffer( buffer );
                txGetter.extract( blockLogBuffer );
                blockLogBuffer.done();
            }
        }, new Deserializer<Long>()
        {
            @SuppressWarnings( "boxing" )
            public Long read( ChannelBuffer buffer, ByteBuffer temporaryBuffer ) throws IOException
            {
                return buffer.readLong();
            }
        });
    }

    public Response<Void> finishTransaction( SlaveContext context )
    {
        return sendRequest( HaRequestType.FINISH, context, new Serializer()
        {
            public void write( ChannelBuffer buffer, ByteBuffer readBuffer ) throws IOException
            {
            }
        }, VOID_DESERIALIZER );
    }

    public void rollbackOngoingTransactions( SlaveContext context )
    {
        throw new UnsupportedOperationException( "Should never be called from the client side" );
    }

    public Response<Void> pullUpdates( SlaveContext context )
    {
        return sendRequest( HaRequestType.PULL_UPDATES, context, EMPTY_SERIALIZER, VOID_DESERIALIZER );
    }

    public Response<Integer> getMasterIdForCommittedTx( final long txId )
    {
        return sendRequest( HaRequestType.GET_MASTER_ID_FOR_TX, SlaveContext.EMPTY, new Serializer()
        {
            public void write( ChannelBuffer buffer, ByteBuffer readBuffer ) throws IOException
            {
                buffer.writeLong( txId );
            }
        }, INTEGER_DESERIALIZER );
    }

    @SuppressWarnings( "unchecked" )
    public Response<Void> copyStore( SlaveContext context, final StoreWriter writer )
    {
        context = new SlaveContext( context.getSessionId(), context.machineId(),
                context.getEventIdentifier(), new Pair[0] );

        return sendRequest( HaRequestType.COPY_STORE, context, EMPTY_SERIALIZER, new Protocol.FileStreamsDeserializer( writer ) );
    }
    
    public static enum HaRequestType implements RequestType<Master>
    {
        ALLOCATE_IDS( new MasterCaller<Master, IdAllocation>()
        {
            public Response<IdAllocation> callMaster( Master master, SlaveContext context,
                    ChannelBuffer input, ChannelBuffer target )
            {
                IdType idType = IdType.values()[input.readByte()];
                return master.allocateIds( idType );
            }
        }, new ObjectSerializer<IdAllocation>()
        {
            public void write( IdAllocation idAllocation, ChannelBuffer result ) throws IOException
            {
                IdRange idRange = idAllocation.getIdRange();
                result.writeInt( idRange.getDefragIds().length );
                for ( long id : idRange.getDefragIds() )
                {
                    result.writeLong( id );
                }
                result.writeLong( idRange.getRangeStart() );
                result.writeInt( idRange.getRangeLength() );
                result.writeLong( idAllocation.getHighestIdInUse() );
                result.writeLong( idAllocation.getDefragCount() );
            }
        }, false ),
        CREATE_RELATIONSHIP_TYPE( new MasterCaller<Master, Integer>()
        {
            public Response<Integer> callMaster( Master master, SlaveContext context,
                    ChannelBuffer input, ChannelBuffer target )
            {
                return master.createRelationshipType( context, readString( input ) );
            }
        }, INTEGER_SERIALIZER ),
        ACQUIRE_NODE_WRITE_LOCK( new AquireLockCall()
        {
            @Override
            Response<LockResult> lock( Master master, SlaveContext context, long... ids )
            {
                return master.acquireNodeWriteLock( context, ids );
            }
        }, LOCK_SERIALIZER ),
        ACQUIRE_NODE_READ_LOCK( new AquireLockCall()
        {
            @Override
            Response<LockResult> lock( Master master, SlaveContext context, long... ids )
            {
                return master.acquireNodeReadLock( context, ids );
            }
        }, LOCK_SERIALIZER ),
        ACQUIRE_RELATIONSHIP_WRITE_LOCK( new AquireLockCall()
        {
            @Override
            Response<LockResult> lock( Master master, SlaveContext context, long... ids )
            {
                return master.acquireRelationshipWriteLock( context, ids );
            }
        }, LOCK_SERIALIZER ),
        ACQUIRE_RELATIONSHIP_READ_LOCK( new AquireLockCall()
        {
            @Override
            Response<LockResult> lock( Master master, SlaveContext context, long... ids )
            {
                return master.acquireRelationshipReadLock( context, ids );
            }
        }, LOCK_SERIALIZER ),
        COMMIT( new MasterCaller<Master, Long>()
        {
            public Response<Long> callMaster( Master master, SlaveContext context,
                    ChannelBuffer input, ChannelBuffer target )
            {
                String resource = readString( input );
                final ReadableByteChannel reader = new BlockLogReader( input );
                return master.commitSingleResourceTransaction( context, resource,
                        TxExtractor.create( reader ) );
            }
        }, LONG_SERIALIZER ),
        PULL_UPDATES( new MasterCaller<Master, Void>()
        {
            public Response<Void> callMaster( Master master, SlaveContext context,
                    ChannelBuffer input, ChannelBuffer target )
            {
                return master.pullUpdates( context );
            }
        }, VOID_SERIALIZER ),
        FINISH( new MasterCaller<Master, Void>()
        {
            public Response<Void> callMaster( Master master, SlaveContext context,
                    ChannelBuffer input, ChannelBuffer target )
            {
                return master.finishTransaction( context );
            }
        }, VOID_SERIALIZER ),
        GET_MASTER_ID_FOR_TX( new MasterCaller<Master, Integer>()
        {
            public Response<Integer> callMaster( Master master, SlaveContext context,
                    ChannelBuffer input, ChannelBuffer target )
            {
                return master.getMasterIdForCommittedTx( input.readLong() );
            }
        }, INTEGER_SERIALIZER, false ),
        COPY_STORE( new MasterCaller<Master, Void>()
        {
            public Response<Void> callMaster( Master master, SlaveContext context,
                    ChannelBuffer input, final ChannelBuffer target )
            {
                return master.copyStore( context, new ToNetworkStoreWriter( target ) );
            }
            
            byte id()
            {
                return (byte) 255;
            }
        }, VOID_SERIALIZER );

        @SuppressWarnings( "rawtypes" )
        final MasterCaller caller;
        @SuppressWarnings( "rawtypes" )
        final ObjectSerializer serializer;
        private final boolean includesSlaveContext;

        private <T> HaRequestType( MasterCaller caller, ObjectSerializer<T> serializer,
                boolean includesSlaveContext )
        {
            this.caller = caller;
            this.serializer = serializer;
            this.includesSlaveContext = includesSlaveContext;
        }

        private <T> HaRequestType( MasterCaller caller, ObjectSerializer<T> serializer )
        {
            this( caller, serializer, true );
        }
        
        public ObjectSerializer getObjectSerializer()
        {
            return serializer;
        }
        
        public MasterCaller getMasterCaller()
        {
            return caller;
        }
        
        public byte id()
        {
            return (byte) ordinal();
        }

        public boolean includesSlaveContext()
        {
            return this.includesSlaveContext;
        }
    }

    protected static IdAllocation readIdAllocation( ChannelBuffer buffer )
    {
        int numberOfDefragIds = buffer.readInt();
        long[] defragIds = new long[numberOfDefragIds];
        for ( int i = 0; i < numberOfDefragIds; i++ )
        {
            defragIds[i] = buffer.readLong();
        }
        long rangeStart = buffer.readLong();
        int rangeLength = buffer.readInt();
        long highId = buffer.readLong();
        long defragCount = buffer.readLong();
        return new IdAllocation( new IdRange( defragIds, rangeStart, rangeLength ),
                highId, defragCount );
    }

    protected static class AcquireLockSerializer implements Serializer
    {
        private final long[] entities;

        AcquireLockSerializer( long... entities )
        {
            this.entities = entities;
        }

        public void write( ChannelBuffer buffer, ByteBuffer readBuffer ) throws IOException
        {
            buffer.writeInt( entities.length );
            for ( long entity : entities )
            {
                buffer.writeLong( entity );
            }
        }
    }

    static abstract class AquireLockCall implements MasterCaller<Master, LockResult>
    {
        public Response<LockResult> callMaster( Master master, SlaveContext context,
                ChannelBuffer input, ChannelBuffer target )
        {
            long[] ids = new long[input.readInt()];
            for ( int i = 0; i < ids.length; i++ )
            {
                ids[i] = input.readLong();
            }
            return lock( master, context, ids );
        }

        abstract Response<LockResult> lock( Master master, SlaveContext context, long... ids );
    }
}
