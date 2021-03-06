/**
 * Copyright (c) 2002-2012 "Neo Technology,"
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
package org.neo4j.kernel;

import org.neo4j.kernel.ha.ClusterDatabaseInfoProvider;
import org.neo4j.kernel.ha.ClusterMemberInfoProvider;
import org.neo4j.kernel.ha.HighlyAvailableGraphDatabase;
import org.neo4j.management.ClusterDatabaseInfo;
import org.neo4j.management.ClusterMemberInfo;

public class HighlyAvailableKernelData extends KernelData
{
    private final HighlyAvailableGraphDatabase db;
    private final ClusterMemberInfoProvider clusterMemberInfoProvider;
    private final ClusterDatabaseInfoProvider memberInfoProvider;

    public HighlyAvailableKernelData( HighlyAvailableGraphDatabase db, ClusterMemberInfoProvider clusterMemberInfo,
                               ClusterDatabaseInfoProvider memberInfo )
    {
        super( db.getConfig() );
        this.db = db;
        this.clusterMemberInfoProvider = clusterMemberInfo;
        this.memberInfoProvider = memberInfo;
    }

    @Override
    public Version version()
    {
        return Version.getKernel();
    }

    @Override
    public GraphDatabaseAPI graphDatabase()
    {
        return db;
    }

    public ClusterMemberInfo[] getClusterInfo()
    {
        return clusterMemberInfoProvider.getClusterInfo();
    }

    public ClusterDatabaseInfo getMemberInfo()
    {
        return memberInfoProvider.getInfo();
    }
}
