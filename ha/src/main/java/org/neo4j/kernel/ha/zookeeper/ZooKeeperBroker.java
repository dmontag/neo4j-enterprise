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

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.helpers.Pair;
import org.neo4j.kernel.AbstractGraphDatabase;
import org.neo4j.kernel.KernelData;
import org.neo4j.kernel.ha.AbstractBroker;
import org.neo4j.kernel.ha.ConnectionInformation;
import org.neo4j.kernel.ha.HaConfig;
import org.neo4j.kernel.ha.Master;
import org.neo4j.kernel.ha.MasterImpl;
import org.neo4j.kernel.ha.MasterServer;
import org.neo4j.kernel.ha.ResponseReceiver;
import org.neo4j.kernel.impl.nioneo.store.StoreId;
import org.neo4j.management.Neo4jManager;

import javax.management.remote.JMXServiceURL;
import java.util.Map;

public class ZooKeeperBroker extends AbstractBroker
{
    private final ZooClient zooClient;
    private final String haServer;
    private final int machineId;
    private final String clusterName;

    public ZooKeeperBroker( GraphDatabaseService graphDb, int machineId, Map<String, String> config, ResponseReceiver receiver )
    {
        super( machineId, graphDb );

        clusterName = HaConfig.getClusterNameFromConfig( config );
        haServer = HaConfig.getHaServerFromConfig( config );
        this.machineId = machineId;

        String storeDir = ((AbstractGraphDatabase) graphDb).getStoreDir();
        this.zooClient = new ZooClient( getRootPathGetter( storeDir ), receiver, graphDb, config );
    }

    @Override
    public StoreId createCluster( StoreId storeIdSuggestion )
    {
        return zooClient.createCluster( clusterName, storeIdSuggestion );
    }

    private RootPathGetter getRootPathGetter( String storeDir )
    {
        try
        {
            new NeoStoreUtil( storeDir );
            return RootPathGetter.forKnownStore( storeDir );
        }
        catch ( RuntimeException e )
        {
            return RootPathGetter.forUnknownStore( storeDir );
        }
    }

    @Override
    public void setConnectionInformation( KernelData kernel )
    {
        String instanceId = kernel.instanceId();
        JMXServiceURL url = Neo4jManager.getConnectionURL( kernel );
        if ( instanceId != null && url != null )
        {
            zooClient.setJmxConnectionData( url, instanceId );
        }
    }

    @Override
    public ConnectionInformation getConnectionInformation( int machineId )
    {
        for ( ConnectionInformation connection : getConnectionInformation() )
        {
            if ( connection.getMachineId() == machineId ) return connection;
        }
        return null;
    }

    @Override
    public ConnectionInformation[] getConnectionInformation()
    {
        Map<Integer, Machine> machines = zooClient.getAllMachines( false );
        Machine master = zooClient.getMasterBasedOn( machines.values() );
        ConnectionInformation[] result = new ConnectionInformation[machines.size()];
        int i = 0;
        for ( Machine machine : machines.values() )
        {
            result[i++] = addJmxInfo( new ConnectionInformation( machine, master.equals( machine ) ) );
        }
        return result;
    }

    private ConnectionInformation addJmxInfo( ConnectionInformation connect )
    {
        zooClient.getJmxConnectionData( connect );
        return connect;
    }

    public Pair<Master, Machine> getMaster()
    {
        return zooClient.getCachedMaster();
    }

    public Pair<Master, Machine> getMasterReally()
    {
        return zooClient.getMasterFromZooKeeper( true );
    }

    @Override
    public Machine getMasterExceptMyself()
    {
        Map<Integer, Machine> machines = zooClient.getAllMachines( true );
        machines.remove( this.machineId );
        return zooClient.getMasterBasedOn( machines.values() );
    }

    public Object instantiateMasterServer( GraphDatabaseService graphDb )
    {
        MasterServer server = new MasterServer( new MasterImpl( graphDb ),
                Machine.splitIpAndPort( haServer ).other(), getStoreDir() );
        return server;
    }

    @Override
    public void setLastCommittedTxId( long txId )
    {
        zooClient.setCommittedTx( txId );
    }

    public boolean iAmMaster()
    {
        return zooClient.getCachedMaster().other().getMachineId() == getMyMachineId();
    }

    @Override
    public void shutdown()
    {
        zooClient.shutdown();
    }

    @Override
    public void rebindMaster()
    {
        zooClient.setDataChangeWatcher( ZooClient.MASTER_REBOUND_CHILD, machineId );
    }
    
    @Override
    public void notifyMasterChange( Machine newMaster )
    {
        zooClient.setDataChangeWatcher( ZooClient.MASTER_NOTIFY_CHILD, newMaster.getMachineId(), true );
    }
}
