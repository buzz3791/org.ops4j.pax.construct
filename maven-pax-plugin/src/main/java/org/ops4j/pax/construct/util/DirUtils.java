package org.ops4j.pax.construct.util;

/*
 * Copyright 2007 Stuart McCulloch, Alin Dreghiciu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.UnArchiver;
import org.codehaus.plexus.archiver.manager.ArchiverManager;
import org.codehaus.plexus.archiver.manager.NoSuchArchiverException;
import org.ops4j.pax.construct.util.PomUtils.ExistingElementException;
import org.ops4j.pax.construct.util.PomUtils.Pom;

/**
 * Various utility methods for managing and refactoring directories and paths
 */
public final class DirUtils
{
    /**
     * Hide constructor for utility class
     */
    private DirUtils()
    {
    }

    /**
     * Resolve a file to its unique canonical path - null resolves to the current directory
     * 
     * @param file file, may be null
     * @param ignoreErrors ignore checked exceptions when true
     * @return file representing the canonical path
     */
    public static File resolveFile( File file, boolean ignoreErrors )
    {
        File candidate = file;
        if( null == file )
        {
            candidate = new File( "." );
        }

        try
        {
            candidate = candidate.getCanonicalFile();
        }
        catch( IOException e )
        {
            if( !ignoreErrors )
            {
                throw new RuntimeException( e );
            }
        }

        return candidate;
    }

    /**
     * Search the local project tree for a Maven POM with the given id
     * 
     * @param baseDir directory in the project tree
     * @param pomId either artifactId or groupId:artifactId
     * @return a Maven POM with the given id, null if not found
     * @throws IOException
     */
    public static Pom findPom( File baseDir, String pomId )
        throws IOException
    {
        // no searching required
        if( null == pomId || pomId.length() == 0 )
        {
            return null;
        }

        // check id for groupId:artifactId
        int groupMarker = pomId.indexOf( ':' );

        String groupId;
        String artifactId;

        if( groupMarker > 0 )
        {
            groupId = pomId.substring( 0, groupMarker );
            artifactId = pomId.substring( groupMarker + 1 );
        }
        else
        {
            groupId = null;
            artifactId = pomId;
        }

        Pom pom = PomUtils.readPom( baseDir );

        // keep track of search
        Set visited = new HashSet();
        visited.add( pom.getId() );

        while( null != pom )
        {
            if( sameProject( pom, groupId, artifactId ) )
            {
                return pom;
            }

            // use depth-first search pattern
            Pom subPom = nextModule( pom, visited );

            if( null == subPom )
            {
                // no more modules, backtrack
                pom = pom.getContainingPom();
            }
            else
            {
                pom = subPom;
            }
        }

        // exhausted project tree
        return null;
    }

    /**
     * @param pom Maven project model
     * @param groupId optional project group id
     * @param artifactId project artifact id or bundle symbolic name
     * @return true if the project has matching ids, otherwise false
     */
    static boolean sameProject( Pom pom, String groupId, String artifactId )
    {
        if( ( artifactId.equals( pom.getArtifactId() ) || artifactId.equals( pom.getBundleSymbolicName() ) )
            && ( null == groupId || groupId.equals( pom.getGroupId() ) ) )
        {
            return true;
        }

        return false;
    }

    /**
     * @param pom current Maven project
     * @param visited already visited projects
     * @return next project to visit, null if local search is complete
     */
    static Pom nextModule( Pom pom, Set visited )
    {
        for( Iterator i = pom.getModuleNames().iterator(); i.hasNext(); )
        {
            Pom subPom = pom.getModulePom( (String) i.next() );

            // avoid re-visiting modules
            if( visited.add( subPom.getId() ) )
            {
                return subPom;
            }
        }

        return null;
    }

    /**
     * Verify all Maven POMs from the base directory to the target, adding missing POMs as required
     * 
     * @param baseDir base directory
     * @param targetDir target directory
     * @return the Maven project in the target directory
     * @throws IOException
     */
    public static Pom createModuleTree( File baseDir, File targetDir )
        throws IOException
    {
        // shortcut: target directory already has a POM
        File pomFile = new File( targetDir, "pom.xml" );
        if( pomFile.exists() )
        {
            return PomUtils.readPom( pomFile );
        }

        String[] pivot = calculateRelativePath( baseDir, targetDir );
        if( null == pivot )
        {
            // unable to find common parent directory!
            return null;
        }

        File commonDir = new File( pivot[1] );
        String descentPath = pivot[2];

        // start checking path from common directory
        Pom parentPom = PomUtils.readPom( commonDir );
        Pom childPom = parentPom;

        int i = 0;
        for( int j = descentPath.indexOf( '/' ); j >= 0; i = j + 1, j = descentPath.indexOf( '/', i ) )
        {
            // check the next module pom...
            pomFile = new File( commonDir, descentPath.substring( 0, j ) + "/pom.xml" );

            if( pomFile.exists() )
            {
                childPom = PomUtils.readPom( pomFile );
            }
            else
            {
                try
                {
                    // no such pom, need to create new module pom
                    String module = descentPath.substring( i, j );

                    // link parent to new module pom
                    parentPom.addModule( module, true );
                    parentPom.write();

                    String groupId = PomUtils.getCompoundId( parentPom.getGroupId(), parentPom.getArtifactId() );

                    // create missing module pom and link back to parent
                    childPom = PomUtils.createPom( pomFile, groupId, module );
                    childPom.setParent( parentPom, null, true );
                    childPom.write();
                }
                catch( ExistingElementException e )
                {
                    // this should never happen
                    throw new RuntimeException( e );
                }
            }

            // descend to next pom
            parentPom = childPom;
        }

        // final pom in target directory
        return childPom;
    }

    /**
     * Calculate the relative path (and common directory) to get from a base directory to a target directory
     * 
     * @param baseDir base directory
     * @param targetDir target directory
     * @return three strings: a dotted path, absolute location of the common directory, and a descent path
     */
    public static String[] calculateRelativePath( File baseDir, File targetDir )
    {
        // need canonical form for this to work
        File from = resolveFile( baseDir, true );
        File to = resolveFile( targetDir, true );

        StringBuffer dottedPath = new StringBuffer();
        StringBuffer descentPath = new StringBuffer();

        while( !from.equals( to ) )
        {
            // "from" is above "to", so need to descend...
            if( from.getPath().length() < to.getPath().length() )
            {
                descentPath.insert( 0, to.getName() + '/' );
                to = to.getParentFile();
            }
            // otherwise need to move up (ie "..")
            else
            {
                dottedPath.append( "../" );
                from = from.getParentFile();
            }

            // reached top of file-system!
            if( null == from || null == to )
            {
                return null;
            }
        }

        return new String[]
        {
            // both "from" and "to" now hold the common directory
            dottedPath.toString(), to.getPath(), descentPath.toString()
        };
    }

    /**
     * Refactor path string, adding base directory to all entries
     * 
     * @param path path to refactor
     * @param baseDir base directory to add
     * @param pathSeparator path separator
     * @return the refactored path
     */
    public static String rebasePaths( String path, String baseDir, char pathSeparator )
    {
        if( null == path )
        {
            return baseDir;
        }

        String[] entries = path.split( Character.toString( pathSeparator ) );

        StringBuffer rebasedPath = new StringBuffer();
        for( int i = 0; i < entries.length; i++ )
        {
            if( i > 0 )
            {
                rebasedPath.append( pathSeparator );
            }

            if( ".".equals( entries[i] ) )
            {
                // dot entry is special case
                rebasedPath.append( baseDir );
            }
            else
            {
                rebasedPath.append( baseDir );
                rebasedPath.append( '/' );
                rebasedPath.append( entries[i] );
            }
        }

        return rebasedPath.toString();
    }

    /**
     * Expand any bundle entries on the classpath to include their unpacked contents (useful when compiling)
     * 
     * @param outputDir current output directory
     * @param path list of classpath elements
     * @param archiverManager creates unarchiver objects
     * @param tempDir temporary directory for unpacking
     * @return expanded classpath
     */
    public static List expandOSGiClassPath( File outputDir, List path, ArchiverManager archiverManager, File tempDir )
    {
        List expandedPath = new ArrayList();

        for( Iterator i = path.iterator(); i.hasNext(); )
        {
            // original should appear before expanded entries
            File element = new File( (String) i.next() );
            expandedPath.add( element.getPath() );

            // don't expand current project
            if( element.equals( outputDir ) )
            {
                continue;
            }

            File bundle = locateBundle( element );

            if( bundle != null && bundle.getName().endsWith( ".jar" ) )
            {
                String bundleClassPath = extractBundleClassPath( bundle );
                if( !".".equals( bundleClassPath ) )
                {
                    File here = new File( tempDir, bundle.getName() );

                    if( unpackBundle( archiverManager, bundle, here ) )
                    {
                        // refactor bundle classpath to point to the recently unpacked contents and append it
                        String rebasedClassPath = DirUtils.rebasePaths( bundleClassPath, here.getPath(), ',' );
                        expandedPath.addAll( Arrays.asList( rebasedClassPath.split( "," ) ) );
                    }
                }
            }
        }

        return expandedPath;
    }

    /**
     * Locate the actual bundle for the given classpath element
     * 
     * @param classpathElement classpath element, may be myProject/target/classes
     * @return the final bundle, null if it hasn't been built yet
     */
    static File locateBundle( File classpathElement )
    {
        File bundle = classpathElement;

        // for now just assume the output directory is target/classes
        String outputDir = "target" + File.separator + "classes";
        String path = bundle.getPath();

        if( path.endsWith( outputDir ) )
        {
            // we need the final bundle, not the output directory, so load up the project POM
            File projectDir = bundle.getParentFile().getParentFile();

            try
            {
                Pom reactorPom = PomUtils.readPom( projectDir );
                if( reactorPom.isBundleProject() )
                {
                    // assume standard artifact name
                    return reactorPom.getFinalBundle();
                }
            }
            catch( IOException e )
            {
                return null;
            }
        }

        // otherwise just assume it's a bundle for now (will check for manifest later)
        return bundle;
    }

    /**
     * @param bundle jarfile
     * @return Bundle-ClassPath
     */
    static String extractBundleClassPath( File bundle )
    {
        String bundleClassPath = null;

        try
        {
            Manifest manifest = new JarFile( bundle ).getManifest();
            Attributes mainAttributes = manifest.getMainAttributes();
            bundleClassPath = mainAttributes.getValue( "Bundle-ClassPath" );
        }
        catch( IOException e )
        {
            System.err.println( "WARNING: unable to read jarfile " + bundle );
        }

        if( bundleClassPath != null )
        {
            return bundleClassPath;
        }
        else
        {
            return ".";
        }
    }

    /**
     * @param archiverManager creates unarchiver objects
     * @param bundle jarfile
     * @param here unpack directory
     * @return true if bundle was successfully unpacked
     */
    public static boolean unpackBundle( ArchiverManager archiverManager, File bundle, File here )
    {
        try
        {
            UnArchiver unArchiver = archiverManager.getUnArchiver( bundle );

            here.mkdirs();
            unArchiver.setDestDirectory( here );
            unArchiver.setSourceFile( bundle );
            unArchiver.extract();

            return true;
        }
        catch( NoSuchArchiverException e )
        {
            return false;
        }
        catch( ArchiverException e )
        {
            return false;
        }
        catch( IOException e )
        {
            return false;
        }
    }

    /**
     * Recursively delete (prune) all empty directories underneath the base directory
     * 
     * @param baseDir base directory
     */
    public static void pruneEmptyFolders( File baseDir )
    {
        List candidates = new ArrayList();
        candidates.add( baseDir );

        List prunable = new ArrayList();
        while( !candidates.isEmpty() )
        {
            File f = (File) candidates.remove( 0 );
            if( f.isDirectory() )
            {
                File[] files = f.listFiles();
                candidates.addAll( Arrays.asList( files ) );
                prunable.add( f );
            }
        }

        // delete must go in reverse
        Collections.reverse( prunable );

        for( Iterator i = prunable.iterator(); i.hasNext(); )
        {
            // only deletes empty directories
            File directory = (File) i.next();
            directory.delete();
        }
    }
}
