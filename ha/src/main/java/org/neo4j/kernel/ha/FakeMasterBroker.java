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

import org.neo4j.com.Protocol;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.helpers.Pair;
import org.neo4j.kernel.ha.zookeeper.Machine;
import org.neo4j.kernel.impl.nioneo.store.StoreId;

public class FakeMasterBroker extends AbstractBroker
{
    public FakeMasterBroker( int myMachineId, GraphDatabaseService graphDb )
    {
        super( myMachineId, graphDb );
    }

    @Override
    public StoreId createCluster( StoreId storeIdSuggestion )
    {
        return storeIdSuggestion; // Master will always win
    }

    public Machine getMasterMachine()
    {
        return new Machine( getMyMachineId(), 0, 1, -1, null );
    }

    public Pair<Master, Machine> getMaster()
    {
        return Pair.<Master, Machine>of( null, new Machine( getMyMachineId(), 0, 1, -1, null ) );
        // throw new UnsupportedOperationException( "I am master" );
    }

    public Pair<Master, Machine> getMasterReally()
    {
        return Pair.<Master, Machine>of( null, new Machine( getMyMachineId(), 0, 1, -1, null ) );
    }

    public boolean iAmMaster()
    {
        return getMyMachineId() == 0;
    }

    public Object instantiateMasterServer( GraphDatabaseService graphDb )
    {
        return new MasterServer( new MasterImpl( graphDb ), Protocol.PORT, getStoreDir() );
    }
}
