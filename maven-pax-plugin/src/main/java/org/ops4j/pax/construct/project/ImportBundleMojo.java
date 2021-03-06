package org.ops4j.pax.construct.project;

/*
 * Copyright 2007 Stuart McCulloch
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DistributionManagement;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.artifact.InvalidDependencyVersionException;
import org.ops4j.pax.construct.util.DirUtils;
import org.ops4j.pax.construct.util.ExcludeSystemBundlesFilter;
import org.ops4j.pax.construct.util.PomUtils;
import org.ops4j.pax.construct.util.PomUtils.Pom;

/**
 * Import an OSGi bundle as a project dependency and mark it for deployment
 * 
 * <code><pre>
 *   mvn pax:import-bundle [-DgroupId=...] -DartifactId=... [-Dversion=...]
 * </pre></code>
 * 
 * @goal import-bundle
 * @aggregator true
 * 
 * @requiresProject false
 */
public class ImportBundleMojo extends AbstractMojo
{
    /**
     * Component factory for Maven artifacts
     * 
     * @component
     */
    private ArtifactFactory m_factory;

    /**
     * Component for resolving Maven artifacts
     * 
     * @component
     */
    private ArtifactResolver m_resolver;

    /**
     * Component for resolving Maven metadata
     * 
     * @component
     */
    private ArtifactMetadataSource m_source;

    /**
     * Component factory for Maven projects
     * 
     * @component
     */
    private MavenProjectBuilder m_projectBuilder;

    /**
     * List of remote Maven repositories for the containing project.
     * 
     * @parameter expression="${project.remoteArtifactRepositories}"
     * @required
     * @readonly
     */
    private List m_remoteRepos;

    /**
     * The local Maven repository for the containing project.
     * 
     * @parameter expression="${localRepository}"
     * @required
     * @readonly
     */
    private ArtifactRepository m_localRepo;

    /**
     * The groupId of the bundle to be imported.
     * 
     * @parameter expression="${groupId}"
     */
    private String groupId;

    /**
     * The artifactId of the bundle to be imported.
     * 
     * @parameter expression="${artifactId}"
     * @required
     */
    private String artifactId;

    /**
     * The version of the bundle to be imported.
     * 
     * @parameter expression="${version}"
     */
    private String version;

    /**
     * Comma-separated list of artifacts (use groupId:artifactId) to exclude from importing.
     * 
     * @parameter expression="${exclusions}"
     */
    private String exclusions;

    /**
     * Reference to the project's provision POM (use artifactId or groupId:artifactId).
     * 
     * @parameter expression="${provisionId}" default-value="provision"
     */
    private String provisionId;

    /**
     * Target directory where the bundle should be imported.
     * 
     * @parameter expression="${targetDirectory}" default-value="${project.basedir}"
     */
    private File targetDirectory;

    /**
     * When true, also try to import any provided dependencies of imported bundles.
     * 
     * @parameter expression="${importTransitive}"
     */
    private boolean importTransitive;

    /**
     * When true, also try to import optional dependencies of imported bundles.
     * 
     * @parameter expression="${importOptional}"
     */
    private boolean importOptional;

    /**
     * When true, also consider compile and runtime dependencies as potential bundles.
     * 
     * @parameter expression="${widenScope}"
     */
    private boolean widenScope;

    /**
     * When true, check dependency artifacts for OSGi metadata before wrapping them.
     * 
     * @parameter expression="${testMetadata}" default-value="true"
     */
    private boolean testMetadata;

    /**
     * When false, mark the imported bundle as optional so it won't be provisioned.
     * 
     * @parameter expression="${deploy}" default-value="true"
     */
    private boolean deploy;

    /**
     * When true, overwrite existing entries with the new imports.
     * 
     * @parameter expression="${overwrite}" default-value="true"
     */
    private boolean overwrite;

    /**
     * The local provisioning POM, where imported non-local bundles are recorded.
     */
    private Pom m_provisionPom;

    /**
     * The bundle POM in the target directory.
     */
    private Pom m_localBundlePom;

    /**
     * A list of potential artifacts (groupId:artifactId:version) to be imported
     */
    private List m_candidateIds;

    /**
     * A list of artifacts (groupId:artifactId) that have already been processed.
     */
    private Set m_visitedIds;

    /**
     * {@inheritDoc}
     */
    public void execute()
        throws MojoExecutionException
    {
        populateMissingFields();

        // Find host POMs which will receive the imported dependencies
        m_provisionPom = DirUtils.findPom( targetDirectory, provisionId );
        m_localBundlePom = readBundlePom( targetDirectory );

        if( null == m_provisionPom && null == m_localBundlePom )
        {
            throw new MojoExecutionException( "Cannot execute command."
                + " It requires a project with an existing pom.xml, but the build is not using one." );
        }

        String rootId = groupId + ':' + artifactId + ':' + version;

        m_candidateIds = new ArrayList();
        m_visitedIds = new HashSet();

        // kickstart the import
        excludeCandidates( exclusions );
        scheduleCandidate( rootId );
        importBundles( rootId );

        // save any dependency updates
        writeUpdatedPom( m_localBundlePom );
        writeUpdatedPom( m_provisionPom );
    }

    /**
     * @param rootId initial import
     */
    private void importBundles( String rootId )
    {
        while( !m_candidateIds.isEmpty() )
        {
            String id = (String) m_candidateIds.remove( 0 );
            String[] fields = id.split( ":" );

            MavenProject p = buildMavenProject( fields[0], fields[1], fields[2] );
            if( null == p )
            {
                continue;
            }

            if( "pom".equals( p.getPackaging() ) )
            {
                // support 'dependency' POMs
                processDependencies( p );
            }
            else if( rootId.equals( id ) /* user knows best: assume given artifact is a bundle */
                || PomUtils.isBundleProject( p, m_resolver, m_remoteRepos, m_localRepo, testMetadata ) )
            {
                importBundle( p );

                // stop at first bundle
                if( !importTransitive )
                {
                    break;
                }

                processDependencies( p );
            }
            else
            {
                getLog().info( "Ignoring non-bundle dependency " + p.getId() );
            }
        }
    }

    /**
     * Populate missing fields with information from the local project and Maven repository
     * 
     * @throws MojoExecutionException
     */
    private void populateMissingFields()
        throws MojoExecutionException
    {
        if( PomUtils.isEmpty( groupId ) )
        {
            Pom localPom = DirUtils.findPom( targetDirectory, artifactId );
            if( localPom != null )
            {
                // use the groupId from the POM
                groupId = localPom.getGroupId();

                if( PomUtils.needReleaseVersion( version ) )
                {
                    // also grab version if missing
                    version = localPom.getVersion();
                }
            }
            else
            {
                // this is a common assumption
                groupId = artifactId;
            }
        }

        if( PomUtils.needReleaseVersion( version ) )
        {
            Artifact artifact = m_factory.createBuildArtifact( groupId, artifactId, "RELEASE", "jar" );
            version = PomUtils.getReleaseVersion( artifact, m_source, m_remoteRepos, m_localRepo, null );
        }
    }

    /**
     * @param pom the Maven POM to write
     */
    private void writeUpdatedPom( Pom pom )
    {
        if( pom != null )
        {
            try
            {
                pom.write();
            }
            catch( IOException e )
            {
                getLog().warn( "Unable to update " + pom );
            }
        }
    }

    /**
     * @param here a Maven POM, or a directory containing a file named 'pom.xml'
     * @return the POM, null if the POM is not a bundle project or doesn't exist
     */
    private static Pom readBundlePom( File here )
    {
        try
        {
            Pom bundlePom = PomUtils.readPom( here );
            if( null != bundlePom && bundlePom.isBundleProject() )
            {
                return bundlePom;
            }

            return null;
        }
        catch( IOException e )
        {
            return null;
        }
    }

    /**
     * Resolve the Maven project for the given artifact, handling when a POM cannot be found in the repository
     * 
     * @param pomGroupId project group id
     * @param pomArtifactId project artifact id
     * @param pomVersion project version
     * @return resolved Maven project
     */
    private MavenProject buildMavenProject( String pomGroupId, String pomArtifactId, String pomVersion )
    {
        Artifact pomArtifact = m_factory.createProjectArtifact( pomGroupId, pomArtifactId, pomVersion );
        MavenProject project;
        try
        {
            project = m_projectBuilder.buildFromRepository( pomArtifact, m_remoteRepos, m_localRepo );
        }
        catch( ProjectBuildingException e )
        {
            getLog().warn( "Problem resolving project " + pomArtifact.getId() );
            return null;
        }

        /*
         * look to see if this is a local project (if so then set the POM location)
         */
        Pom localPom = DirUtils.findPom( targetDirectory, pomGroupId + ':' + pomArtifactId );
        if( localPom != null )
        {
            project.setFile( localPom.getFile() );
        }

        /*
         * Repair stubs (ie. when a POM couldn't be found in the various repositories)
         */
        DistributionManagement dm = project.getDistributionManagement();
        if( dm != null && "generated".equals( dm.getStatus() ) )
        {
            if( localPom != null )
            {
                // local project, use values from the local POM
                project.setPackaging( localPom.getPackaging() );
                project.setName( localPom.getId() );
            }
            else
            {
                // remote project - assume it creates a jarfile (so we can test later for OSGi metadata)
                Artifact jar = m_factory.createBuildArtifact( pomGroupId, pomArtifactId, pomVersion, "jar" );
                project.setArtifact( jar );

                project.setPackaging( "jar" );
                project.setName( jar.getId() );
            }
        }

        return project;
    }

    /**
     * Search direct dependencies for more import candidates
     * 
     * @param project the Maven project being imported
     */
    private void processDependencies( MavenProject project )
    {
        try
        {
            /*
             * exclude common OSGi system bundles, as they don't need to be imported or provisioned
             */
            Set artifacts = project.createArtifacts( m_factory, null, new ExcludeSystemBundlesFilter() );
            for( Iterator i = artifacts.iterator(); i.hasNext(); )
            {
                Artifact artifact = (Artifact) i.next();
                String candidateId = getCandidateId( artifact );
                String scope = artifact.getScope();

                scope = adjustDependencyScope( scope );

                if( !importOptional && artifact.isOptional() )
                {
                    getLog().info( "Skipping optional dependency " + artifact );
                }
                else if( Artifact.SCOPE_PROVIDED.equals( scope ) )
                {
                    scheduleCandidate( candidateId );
                }
                else
                {
                    getLog().info( "Skipping dependency " + artifact );
                }
            }
        }
        catch( InvalidDependencyVersionException e )
        {
            getLog().warn( "Problem resolving dependencies for " + project.getId() );
        }
    }

    /**
     * @param candidateId potential new candidate
     */
    private void scheduleCandidate( String candidateId )
    {
        int versionIndex = candidateId.lastIndexOf( ':' );
        if( m_visitedIds.add( candidateId.substring( 0, versionIndex ) ) )
        {
            m_candidateIds.add( candidateId );
        }
    }

    /**
     * Support widening of scopes to treat compile and runtime dependencies as provided dependencies
     * 
     * @param scope original dependency scope
     * @return potentially widened scope
     */
    private String adjustDependencyScope( String scope )
    {
        if( widenScope && !Artifact.SCOPE_SYSTEM.equals( scope ) && !Artifact.SCOPE_TEST.equals( scope ) )
        {
            return Artifact.SCOPE_PROVIDED;
        }

        return scope;
    }

    /**
     * @param artifact candidate artifact
     * @return simple unique id
     */
    private static String getCandidateId( Artifact artifact )
    {
        return artifact.getGroupId() + ':' + artifact.getArtifactId() + ':' + PomUtils.getMetaVersion( artifact );
    }

    /**
     * Add bundle as a dependency to the provisioning POM and the local bundle POM, as appropriate
     * 
     * @param project bundle project
     */
    private void importBundle( MavenProject project )
    {
        Dependency dependency = new Dependency();

        dependency.setGroupId( project.getGroupId() );
        dependency.setArtifactId( project.getArtifactId() );
        dependency.setVersion( project.getVersion() );
        dependency.setOptional( !deploy );

        // only add non-local bundles to the provisioning POM
        if( m_provisionPom != null && project.getFile() == null )
        {
            getLog().info( "Importing " + project.getName() + " to " + m_provisionPom );
            m_provisionPom.addDependency( dependency, overwrite );
        }

        if( m_localBundlePom != null )
        {
            // use provided scope when adding to bundle pom
            dependency.setScope( Artifact.SCOPE_PROVIDED );

            getLog().info( "Adding " + project.getName() + " as dependency to " + m_localBundlePom );
            m_localBundlePom.addDependency( dependency, overwrite );
        }
    }

    /**
     * Explicitly exclude artifacts from the import process
     * 
     * @param artifacts comma-separated list of artifacts to exclude from importing
     */
    private void excludeCandidates( String artifacts )
    {
        if( PomUtils.isEmpty( artifacts ) )
        {
            return;
        }

        String[] exclusionIds = artifacts.split( "," );
        for( int i = 0; i < exclusionIds.length; i++ )
        {
            String id = exclusionIds[i].trim();
            String[] fields = id.split( ":" );
            if( fields.length > 1 )
            {
                // handle groupId:artifactId:other:stuff
                m_visitedIds.add( fields[0] + ':' + fields[1] );
            }
            else
            {
                // assume groupId same as artifactId
                m_visitedIds.add( id + ':' + id );
            }
        }
    }
}
