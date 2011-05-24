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
package just.testing;

import static java.lang.Thread.sleep;
import static just.testing.ReadSomeStuff.findRandomNode;

import java.util.Random;

import javax.ws.rs.core.MediaType;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;

public class WriteSomeStuff
{
    static final Client CLIENT = Client.create();
    static final String BASE_URI = "http://localhost:7474/db/data/";
    static final Random RANDOM = new Random();
    
    public static void main( String[] args ) throws InterruptedException
    {
        while ( true )
        {
            if ( RANDOM.nextFloat() <= 0.3f )
            {
                createRelationship( findRandomNode(), createNewNode() );
            }
            else
            {
                String from = findRandomNode();
                String to = findRandomNode();
                if ( !from.equals( to ) )
                {
                    createRelationship( from, to );
                }
            }
            sleep( 1000 );
        }
    }
    
    private static void createRelationship( String fromNode, String toNode )
    {
        String json = "{\"to\":\"" + toNode + "\", \"type\":\"SCRIPTED\"}";
        ClientResponse response = CLIENT.resource( fromNode + "/relationships/" )
                .entity( json, MediaType.APPLICATION_JSON_TYPE )
                .accept( MediaType.APPLICATION_JSON_TYPE ).post( ClientResponse.class );
        if ( response.getStatus() != 201 )
        {
            throw new RuntimeException( "Couldn't create it" );
        }
        System.out.println( "Created relationship " + response.getLocation().toString() + " between " + fromNode + " and " + toNode );
        response.close();
    }

    private static String createNewNode()
    {
        ClientResponse response = CLIENT.resource( BASE_URI + "node" )
                .accept( MediaType.APPLICATION_JSON_TYPE ).post( ClientResponse.class );
        if ( response.getStatus() != 201 )
        {
            throw new RuntimeException( "Couldn't create it" );
        }
        String result = response.getLocation().toString();
        System.out.println( "Created node " + result );
        response.close();
        return result;
    }
}
