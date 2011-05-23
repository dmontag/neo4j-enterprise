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
package org.neo4j.kernel.ha.zookeeper;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.List;

import javax.management.remote.JMXServiceURL;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.helpers.Pair;
import org.neo4j.kernel.AbstractGraphDatabase;
import org.neo4j.kernel.ha.ConnectionInformation;
import org.neo4j.kernel.ha.Master;
import org.neo4j.kernel.ha.ResponseReceiver;
import org.neo4j.kernel.impl.nioneo.store.StoreId;
import org.neo4j.kernel.impl.util.StringLogger;

public class ZooClient extends AbstractZooKeeperManager
{
    static final String MASTER_NOTIFY_CHILD = "master-notify";
    static final String MASTER_REBOUND_CHILD = "master-rebound";

    private ZooKeeper zooKeeper;
    private final int machineId;
    private String sequenceNr;

    private long committedTx;

    private final Object keeperStateMonitor = new Object();
    private volatile KeeperState keeperState = KeeperState.Disconnected;
    private volatile boolean shutdown = false;
    private final RootPathGetter rootPathGetter;
    private String rootPath;
    
    // Has the format <host-name>:<port>
    private final String haServer;

    private final StringLogger msgLog;

    private long sessionId = -1;
    private final ResponseReceiver receiver;
    private final int backupPort;

    public ZooClient( String servers, int machineId, RootPathGetter rootPathGetter,
            ResponseReceiver receiver, String haServer, int backupPort, GraphDatabaseService graphDb )
    {
        super( servers, graphDb );
        this.receiver = receiver;
        this.rootPathGetter = rootPathGetter;
        this.haServer = haServer;
        this.machineId = machineId;
        this.backupPort = backupPort;
        this.sequenceNr = "not initialized yet";
        String storeDir = ((AbstractGraphDatabase) graphDb).getStoreDir();
        this.msgLog = StringLogger.getLogger( storeDir );
        this.zooKeeper = instantiateZooKeeper();
    }

    @Override
    protected int getMyMachineId()
    {
        return this.machineId;
    }

    public void process( WatchedEvent event )
    {
        try
        {
            String path = event.getPath();
            msgLog.logMessage( this + ", " + new Date() + " Got event: " + event + "(path=" + path + ")", true );
            if ( path == null && event.getState() == Watcher.Event.KeeperState.Expired )
            {
                keeperState = KeeperState.Expired;
                if ( zooKeeper != null )
                {
                    try
                    {
                        zooKeeper.close();
                    }
                    catch ( InterruptedException e )
                    {
                        e.printStackTrace();
                        Thread.interrupted();
                    }
                }
                zooKeeper = instantiateZooKeeper();
            }
            else if ( path == null && event.getState() == Watcher.Event.KeeperState.SyncConnected )
            {
                long newSessionId = zooKeeper.getSessionId();
                Pair<Master, Machine> masterBeforeIWrite = getMasterFromZooKeeper( false );
                msgLog.logMessage( "Get master before write:" + masterBeforeIWrite );
                boolean masterBeforeIWriteDiffers = masterBeforeIWrite.other().getMachineId() != getCachedMaster().other().getMachineId();
                if ( newSessionId != sessionId || masterBeforeIWriteDiffers )
                {
                    sequenceNr = setup();
                    msgLog.logMessage( "Did setup, seq=" + sequenceNr + " new sessionId=" + newSessionId );
                    keeperState = KeeperState.SyncConnected;
                    Pair<Master, Machine> masterAfterIWrote = getMasterFromZooKeeper( false );
                    msgLog.logMessage( "Get master after write:" + masterAfterIWrote );
                    int masterId = masterAfterIWrote.other().getMachineId();
                    msgLog.logMessage( "Setting '" + MASTER_NOTIFY_CHILD + "' to " + masterId );
                    setDataChangeWatcher( MASTER_NOTIFY_CHILD, masterId );
                    msgLog.logMessage( "Did set '" + MASTER_NOTIFY_CHILD + "' to " + masterId );
                    if ( sessionId != -1 )
                    {
                        receiver.newMaster( masterAfterIWrote, new Exception() );
                    }
                    sessionId = newSessionId;
                }
                else
                {
                    msgLog.logMessage( "SyncConnected with same session id: " + sessionId );
                    keeperState = KeeperState.SyncConnected;
                }
            }
            else if ( path == null && event.getState() == Watcher.Event.KeeperState.Disconnected )
            {
                keeperState = KeeperState.Disconnected;
            }
            else if ( event.getType() == Watcher.Event.EventType.NodeDataChanged )
            {
                Pair<Master, Machine> currentMaster = getMasterFromZooKeeper( true );
                if ( path.contains( MASTER_NOTIFY_CHILD ) )
                {
                    setDataChangeWatcher( MASTER_NOTIFY_CHILD, -1 );
                    if ( currentMaster.other().getMachineId() == machineId )
                    {
                        receiver.newMaster( currentMaster, new Exception() );
                    }
                }
                else if ( path.contains( MASTER_REBOUND_CHILD ) )
                {
                    setDataChangeWatcher( MASTER_REBOUND_CHILD, -1 );
                    if ( currentMaster.other().getMachineId() != machineId )
                    {
                        receiver.newMaster( currentMaster, new Exception() );
                    }
                }
                else
                {
                    msgLog.logMessage( "Unrecognized data change " + path );
                }
            }
        }
        catch ( RuntimeException e )
        {
            msgLog.logMessage( "Error in ZooClient.process", e, true );
            e.printStackTrace();
            throw e;
        }
        finally
        {
            msgLog.flush();
        }
    }

    @Override
    public void waitForSyncConnected()
    {
        if ( keeperState == KeeperState.SyncConnected )
        {
            return;
        }
        if ( shutdown == true )
        {
            throw new ZooKeeperException( "ZooKeeper client has been shutdwon" );
        }
        long startTime = System.currentTimeMillis();
        long currentTime = startTime;
        synchronized ( keeperStateMonitor )
        {
            do
            {
                try
                {
                    keeperStateMonitor.wait( 250 );
                }
                catch ( InterruptedException e )
                {
                    Thread.interrupted();
                }
                if ( keeperState == KeeperState.SyncConnected )
                {
                    return;
                }
                if ( shutdown == true )
                {
                    throw new ZooKeeperException( "ZooKeeper client has been shutdwon" );
                }
                currentTime = System.currentTimeMillis();
            }
            while ( (currentTime - startTime) < SESSION_TIME_OUT );

            if ( keeperState != KeeperState.SyncConnected )
            {
                throw new ZooKeeperTimedOutException(
                        "Connection to ZooKeeper server timed out, keeper state=" + keeperState );
            }
        }
    }

    protected void setDataChangeWatcher( String child, int currentMasterId )
    {
        setDataChangeWatcher( child, currentMasterId, false );
    }
    
    protected void setDataChangeWatcher( String child, int currentMasterId, boolean checkIfCurrentMasterIsSame )
    {
        try
        {
            String root = getRoot();
            String path = root + "/" + child;
            byte[] data = null;
            boolean exists = false;
            try
            {
                data = zooKeeper.getData( path, true, null );
                exists = true;
                
                if ( checkIfCurrentMasterIsSame )
                {
                    int id = ByteBuffer.wrap( data ).getInt();
                    if ( currentMasterId == -1 || id == currentMasterId )
                    {
                        msgLog.logMessage( child + " not set, is already " + currentMasterId );
                        return;
                    }
                }
            }
            catch ( KeeperException e )
            {
                if ( e.code() != KeeperException.Code.NONODE )
                {
                    throw new ZooKeeperException( "Couldn't get master notify node", e );
                }
            }

            // Didn't exist or has changed
            try
            {
                data = new byte[4];
                ByteBuffer.wrap( data ).putInt( currentMasterId );
                if ( !exists )
                {
                    zooKeeper.create( path, data, ZooDefs.Ids.OPEN_ACL_UNSAFE,
                            CreateMode.PERSISTENT );
                    msgLog.logMessage( child + " created with " + currentMasterId );
                }
                else if ( currentMasterId != -1 )
                {
                    zooKeeper.setData( path, data, -1 );
                    msgLog.logMessage( child + " set to " + currentMasterId );
                }

                // Add a watch for it
                zooKeeper.getData( path, true, null );
            }
            catch ( KeeperException e )
            {
                if ( e.code() != KeeperException.Code.NODEEXISTS )
                {
                    throw new ZooKeeperException( "Couldn't set master notify node", e );
                }
            }
        }
        catch ( InterruptedException e )
        {
            Thread.interrupted();
            throw new ZooKeeperException( "Interrupted", e );
        }
    }

    @Override
    public String getRoot()
    {
        makeSureRootPathIsFound();

        // Make sure it exists
        byte[] rootData = null;
        do
        {
            try
            {
                rootData = zooKeeper.getData( rootPath, false, null );
                return rootPath;
            }
            catch ( KeeperException e )
            {
                if ( e.code() != KeeperException.Code.NONODE )
                {
                    throw new ZooKeeperException( "Unable to get root node",
                        e );
                }
            }
            catch ( InterruptedException e )
            {
                Thread.interrupted();
                throw new ZooKeeperException( "Got interrupted", e );
            }
            // try create root
            try
            {
                byte data[] = new byte[0];
                zooKeeper.create( rootPath, data, ZooDefs.Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT );
            }
            catch ( KeeperException e )
            {
                if ( e.code() != KeeperException.Code.NODEEXISTS )
                {
                    throw new ZooKeeperException( "Unable to create root", e );
                }
            }
            catch ( InterruptedException e )
            {
                Thread.interrupted();
                throw new ZooKeeperException( "Got interrupted", e );
            }
        } while ( rootData == null );
        throw new IllegalStateException();
    }

    private void makeSureRootPathIsFound()
    {
        if ( rootPath == null )
        {
            Pair<String, Long> info = rootPathGetter.getRootPath( zooKeeper );
            rootPath = info.first();
            committedTx = info.other();
        }
    }

    private void cleanupChildren()
    {
        try
        {
            String root = getRoot();
            List<String> children = zooKeeper.getChildren( root, false );
            for ( String child : children )
            {
                Pair<Integer, Integer> parsedChild = parseChild( child );
                if ( parsedChild == null )
                {
                    continue;
                }
                if ( parsedChild.first() == machineId )
                {
                    zooKeeper.delete( root + "/" + child, -1 );
                }
            }
        }
        catch ( KeeperException e )
        {
            throw new ZooKeeperException( "Unable to clean up old child", e );
        }
        catch ( InterruptedException e )
        {
            Thread.interrupted();
            throw new ZooKeeperException( "Interrupted.", e );
        }
    }

    private byte[] dataRepresentingMe( long txId )
    {
        byte[] array = new byte[8];
        ByteBuffer buffer = ByteBuffer.wrap( array );
        buffer.putLong( txId );
        return array;
    }

    private String setup()
    {
        try
        {
            cleanupChildren();
            writeHaServerConfig();
            String root = getRoot();
            String path = root + "/" + machineId + "_";
            String created = zooKeeper.create( path, dataRepresentingMe( committedTx ),
                ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL );

            // Add watches to our master notification nodes
            setDataChangeWatcher( MASTER_NOTIFY_CHILD, -1 );
            setDataChangeWatcher( MASTER_REBOUND_CHILD, -1 );
            return created.substring( created.lastIndexOf( "_" ) + 1 );
        }
        catch ( KeeperException e )
        {
            throw new ZooKeeperException( "Unable to setup", e );
        }
        catch ( InterruptedException e )
        {
            Thread.interrupted();
            throw new ZooKeeperException( "Setup got interrupted", e );
        }
        catch ( Throwable t )
        {
            throw new ZooKeeperException( "Unknown setup error", t );
        }
    }

    private void writeHaServerConfig() throws InterruptedException, KeeperException
    {
        // Make sure the HA server root is created
        String path = rootPath + "/" + HA_SERVERS_CHILD;
        try
        {
            zooKeeper.create( path, new byte[0],
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT );
        }
        catch ( KeeperException e )
        {
            if ( e.code() != KeeperException.Code.NODEEXISTS )
            {
                throw e;
            }
        }

        // Write the HA server config.
        String machinePath = path + "/" + machineId;
        byte[] data = haServerAsData();
        try
        {
            zooKeeper.create( machinePath, data,
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL );
        }
        catch ( KeeperException e )
        {
            if ( e.code() != KeeperException.Code.NODEEXISTS )
            {
                throw e;
            }
            msgLog.logMessage( "HA server info already present, trying again" );
            try
            {
                zooKeeper.delete( machinePath, -1 );
            }
            catch ( KeeperException ee )
            {
                ee.printStackTrace();
                // ok
            }
            finally
            {
                writeHaServerConfig();
            }
        }
        zooKeeper.setData( machinePath, data, -1 );
        msgLog.logMessage( "Wrote HA server " + haServer + " to zoo keeper" );
    }

    private byte[] haServerAsData()
    {
        byte[] array = new byte[haServer.length()*2 + 100];
        ByteBuffer buffer = ByteBuffer.wrap( array );
        buffer.putInt( backupPort );
        buffer.put( (byte) haServer.length() );
        buffer.asCharBuffer().put( haServer.toCharArray() ).flip();
        byte[] actualArray = new byte[buffer.limit()];
        System.arraycopy( array, 0, actualArray, 0, actualArray.length );
        return actualArray;
    }

    public synchronized void setJmxConnectionData( JMXServiceURL jmxUrl, String instanceId )
    {
        String path = rootPath + "/" + HA_SERVERS_CHILD + "/" + machineId + "-jmx";
        String url = jmxUrl.toString();
        byte[] data = new byte[( url.length() + instanceId.length() ) * 2 + 4];
        ByteBuffer buffer = ByteBuffer.wrap( data );
        // write URL
        buffer.putShort( (short) url.length() );
        buffer.asCharBuffer().put( url.toCharArray() );
        buffer.position( buffer.position() + url.length() * 2 );
        // write instanceId
        buffer.putShort( (short) instanceId.length() );
        buffer.asCharBuffer().put( instanceId.toCharArray() );
        // truncate array
        if ( buffer.limit() != data.length )
        {
            byte[] array = new byte[buffer.limit()];
            System.arraycopy( data, 0, array, 0, array.length );
            data = array;
        }
        try
        {
            try
            {
                zooKeeper.setData( path, data, -1 );
            }
            catch ( KeeperException e )
            {
                if ( e.code() == KeeperException.Code.NONODE )
                {
                    zooKeeper.create( path, data, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL );
                }
                else
                {
                    msgLog.logMessage( "Unable to set jxm connection info", e );
                }
            }
        }
        catch ( KeeperException e )
        {
            msgLog.logMessage( "Unable to set jxm connection info", e );
        }
        catch ( InterruptedException e )
        {
            Thread.interrupted();
            msgLog.logMessage( "Unable to set jxm connection info", e );
        }
    }

    public void getJmxConnectionData( ConnectionInformation connection )
    {
        String path = rootPath + "/" + HA_SERVERS_CHILD + "/" + machineId + "-jmx";
        byte[] data;
        try
        {
            data = zooKeeper.getData( path, false, null );
        }
        catch ( KeeperException e )
        {
            return;
        }
        catch ( InterruptedException e )
        {
            Thread.interrupted();
            return;
        }
        if ( data == null || data.length == 0 ) return;
        ByteBuffer buffer = ByteBuffer.wrap( data );
        char[] url, instanceId;
        try
        {
            // read URL
            url = new char[buffer.getShort()];
            buffer.asCharBuffer().get( url );
            buffer.position( buffer.position() + url.length * 2 );
            // read instanceId
            instanceId = new char[buffer.getShort()];
            buffer.asCharBuffer().get( instanceId );
        }
        catch ( BufferUnderflowException e )
        {
            return;
        }
        connection.setJMXConnectionData( new String( url ), new String( instanceId ) );
    }

    public synchronized void setCommittedTx( long tx )
    {
//        msgLog.logMessage( "ZooClient setting txId=" + tx + " for machine=" + machineId, true );
        waitForSyncConnected();
        this.committedTx = tx;
        String root = getRoot();
        String path = root + "/" + machineId + "_" + sequenceNr;
        byte[] data = dataRepresentingMe( tx );
        try
        {
            zooKeeper.setData( path, data, -1 );
        }
        catch ( KeeperException e )
        {
            throw new ZooKeeperException( "Unable to set current tx", e );
        }
        catch ( InterruptedException e )
        {
            Thread.interrupted();
            throw new ZooKeeperException( "Interrupted...", e );
        }
    }

    @Override
    public void shutdown()
    {
        this.shutdown = true;
        super.shutdown();
    }

    @Override
    protected ZooKeeper getZooKeeper()
    {
        return zooKeeper;
    }

    @Override
    protected String getHaServer( int machineId, boolean wait )
    {
        return machineId == this.machineId ? haServer : super.getHaServer( machineId, wait );
    }

    public synchronized StoreId createCluster( String clusterName, StoreId storeIdSuggestion )
    {
        String path = "/" + clusterName;
        try
        {
            try
            {
                zooKeeper.create( path, storeIdSuggestion.serialize(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT );
                return storeIdSuggestion; // if successfully written
            }
            catch ( KeeperException e )
            {
                if ( e.code() == KeeperException.Code.NODEEXISTS )
                { // another instance wrote before me
                    try
                    { // read what that instance wrote
                        return StoreId.deserialize( zooKeeper.getData( path, false, null ) );
                    }
                    catch ( KeeperException ex )
                    {
                        throw new ZooKeeperException( "Unable to read cluster store id", ex );
                    }
                }
                else
                {
                    throw new ZooKeeperException( "Unable to write cluster store id", e );
                }
            }
        }
        catch ( InterruptedException e )
        {
            throw new ZooKeeperException( "createCluster interrupted", e );
        }
    }
}
