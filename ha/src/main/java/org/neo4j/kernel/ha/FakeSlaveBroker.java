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

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.helpers.Pair;
import org.neo4j.kernel.ha.zookeeper.Machine;

public class FakeSlaveBroker extends AbstractBroker
{
    private final Master master;
    private final Machine masterMachine;

    public FakeSlaveBroker( Master master, int masterMachineId, int myMachineId, GraphDatabaseService graphDb )
    {
        super( myMachineId, graphDb );
        this.master = master;
        this.masterMachine = new Machine( masterMachineId, 0, 1, -1, null );
    }

    public Pair<Master, Machine> getMaster()
    {
        return Pair.<Master, Machine>of( master, Machine.NO_MACHINE );
    }

    public Pair<Master, Machine> getMasterReally()
    {
        return Pair.<Master, Machine>of( master, Machine.NO_MACHINE );
    }

    public boolean iAmMaster()
    {
        return false;
    }

    public Object instantiateMasterServer( GraphDatabaseService graphDb )
    {
        throw new UnsupportedOperationException();
    }
}
