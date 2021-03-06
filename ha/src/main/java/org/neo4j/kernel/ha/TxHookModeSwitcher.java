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
package org.neo4j.kernel.ha;

import java.net.URI;

import org.neo4j.graphdb.DependencyResolver;
import org.neo4j.kernel.ha.cluster.AbstractModeSwitcher;
import org.neo4j.kernel.ha.cluster.ClusterMemberStateMachine;
import org.neo4j.kernel.impl.core.LockReleaser;
import org.neo4j.kernel.impl.transaction.TxHook;

public class TxHookModeSwitcher extends AbstractModeSwitcher<TxHook>
{
    private final Master master;
    private final RequestContextFactoryResolver requestContextFactory;
    private DependencyResolver resolver;

    public TxHookModeSwitcher( ClusterMemberStateMachine stateMachine,
                               DelegateInvocationHandler<TxHook> delegate, Master master,
                               RequestContextFactoryResolver requestContextFactory, DependencyResolver resolver )
    {
        super( stateMachine, delegate );
        this.master = master;
        this.requestContextFactory = requestContextFactory;
        this.resolver = resolver;
    }

    @Override
    protected TxHook getMasterImpl()
    {
        return new MasterTxHook();
    }

    @Override
    protected TxHook getSlaveImpl( URI serverHaUri )
    {
        return new SlaveTxHook( master, resolver.resolveDependency( LockReleaser.class ),
                resolver.resolveDependency( HaXaDataSourceManager.class ), requestContextFactory );
    }

    public interface RequestContextFactoryResolver
    {
        RequestContextFactory get();
    }
}
