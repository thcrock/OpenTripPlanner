/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.opentripplanner.graph_builder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.google.common.collect.Lists;
import org.opentripplanner.graph_builder.model.GtfsBundle;
import org.opentripplanner.graph_builder.module.*;
import org.opentripplanner.graph_builder.module.map.BusRouteStreetMatcher;
import org.opentripplanner.graph_builder.module.ned.ElevationModule;
import org.opentripplanner.graph_builder.module.ned.GeotiffGridCoverageFactoryImpl;
import org.opentripplanner.graph_builder.module.ned.NEDGridCoverageFactoryImpl;
import org.opentripplanner.graph_builder.module.osm.DefaultWayPropertySetSource;
import org.opentripplanner.graph_builder.module.osm.OpenStreetMapModule;
import org.opentripplanner.graph_builder.services.DefaultStreetEdgeFactory;
import org.opentripplanner.graph_builder.services.GraphBuilderModule;
import org.opentripplanner.graph_builder.services.ned.ElevationGridCoverageFactory;
import org.opentripplanner.openstreetmap.impl.AnyFileBasedOpenStreetMapProviderImpl;
import org.opentripplanner.openstreetmap.services.OpenStreetMapProvider;
import org.opentripplanner.reflect.ReflectionLibrary;
import org.opentripplanner.routing.core.RoutingRequest;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.Graph.LoadLevel;
import org.opentripplanner.routing.impl.DefaultFareServiceFactory;
import org.opentripplanner.standalone.CommandLineParameters;
import org.opentripplanner.standalone.GraphBuilderParameters;
import org.opentripplanner.standalone.OTPMain;
import org.opentripplanner.standalone.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This makes a Graph out of various inputs like GTFS and OSM.
 * It is modular: GraphBuilderModules are placed in a list and run in sequence.
 */
public class GraphBuilder implements Runnable {
    
    private static Logger LOG = LoggerFactory.getLogger(GraphBuilder.class);

    public static final String BUILDER_CONFIG_FILENAME = "build-config.json";

    private List<GraphBuilderModule> _graphBuilderModules = new ArrayList<GraphBuilderModule>();

    private File graphFile;
    
    private boolean _alwaysRebuild = true;

    private List<RoutingRequest> _modeList;
    
    private String _baseGraph = null;
    
    private Graph graph = new Graph();

    /* The router configuration JSON that was discovered during the graph build and will be embedded (if any). */
    public JsonNode routerConfig;

    /** Should the graph be serialized to disk after being created or not? */
    public boolean serializeGraph = true;

    public void addGraphBuilder(GraphBuilderModule loader) {
        _graphBuilderModules.add(loader);
    }

    public void setGraphBuilders(List<GraphBuilderModule> graphLoaders) {
        _graphBuilderModules = graphLoaders;
    }

    public void setAlwaysRebuild(boolean alwaysRebuild) {
        _alwaysRebuild = alwaysRebuild;
    }
    
    public void setBaseGraph(String baseGraph) {
        this._baseGraph = baseGraph;
        try {
            graph = Graph.load(new File(baseGraph), LoadLevel.FULL);
        } catch (Exception e) {
            throw new RuntimeException("error loading base graph");
        }
    }

    public void addMode(RoutingRequest mo) {
        _modeList.add(mo);
    }

    public void setModes(List<RoutingRequest> modeList) {
        _modeList = modeList;
    }
    
    public void setPath (String path) {
        graphFile = new File(path.concat("/Graph.obj"));
    }
    
    public void setPath (File path) {
        graphFile = new File(path, "Graph.obj");
    }

    public Graph getGraph() {
        return this.graph;
    }

    public void run() {
        /* Record how long it takes to build the graph, purely for informational purposes. */
        long startTime = System.currentTimeMillis();

        if (serializeGraph) {
        	
            if (graphFile == null) {
                throw new RuntimeException("graphBuilderTask has no attribute graphFile.");
            }

            if( graphFile.exists() && ! _alwaysRebuild) {
                LOG.info("graph already exists and alwaysRebuild=false => skipping graph build");
                return;
            }
        	
            try {
                if (!graphFile.getParentFile().exists()) {
                    if (!graphFile.getParentFile().mkdirs()) {
                        LOG.error("Failed to create directories for graph bundle at " + graphFile);
                    }
                }
                graphFile.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException("Cannot create or overwrite graph at path " + graphFile);
            }
        }

        // Check all graph builder inputs, and fail fast to avoid waiting until the build process advances.
        for (GraphBuilderModule builder : _graphBuilderModules) {
            builder.checkInputs();
        }
        
        HashMap<Class<?>, Object> extra = new HashMap<Class<?>, Object>();
        for (GraphBuilderModule load : _graphBuilderModules)
            load.buildGraph(graph, extra);

        graph.summarizeBuilderAnnotations();
        if (serializeGraph) {
            try {
                graph.save(graphFile);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        } else {
            LOG.info("Not saving graph to disk, as requested.");
        }

        long endTime = System.currentTimeMillis();
        LOG.info(String.format("Graph building took %.1f minutes.", (endTime - startTime) / 1000 / 60.0));
    }


    /**
     * Factory method to create and configure a GraphBuilder with all the appropriate modules to build a graph from
     * the files in the given directory, accounting for any configuration files located there.
     *
     * TODO parameterize with the router ID and call repeatedly to make multiple builders
     * note of all command line options this is only using  params.inMemory params.preFlight and params.build directory
     */
    public static GraphBuilder forDirectory(CommandLineParameters params, File dir) {
        LOG.info("Wiring up and configuring graph builder task.");
        GraphBuilder graphBuilder = new GraphBuilder();
        List<File> gtfsFiles = Lists.newArrayList();
        List<File> osmFiles =  Lists.newArrayList();
        JsonNode builderConfig = null;
        JsonNode routerConfig = null;
        File demFile = null;
        LOG.info("Searching for graph builder input files in {}", dir);
        if ( ! dir.isDirectory() && dir.canRead()) {
            LOG.error("'{}' is not a readable directory.", dir);
            return null;
        }
        graphBuilder.setPath(dir);
        // Find and parse config files first to reveal syntax errors early without waiting for graph build.
        builderConfig = OTPMain.loadJson(new File(dir, BUILDER_CONFIG_FILENAME));
        GraphBuilderParameters builderParams = new GraphBuilderParameters(builderConfig);
        // Load the router config JSON to fail fast, but we will only apply it later when a router starts up
        graphBuilder.routerConfig = OTPMain.loadJson(new File(dir, Router.ROUTER_CONFIG_FILENAME));
        LOG.info(ReflectionLibrary.dumpFields(builderParams));
        for (File file : dir.listFiles()) {
            switch (InputFileType.forFile(file)) {
                case GTFS:
                    LOG.info("Found GTFS file {}", file);
                    gtfsFiles.add(file);
                    break;
                case OSM:
                    LOG.info("Found OSM file {}", file);
                    osmFiles.add(file);
                    break;
                case DEM:
                    if (!builderParams.fetchElevationUS && demFile == null) {
                        LOG.info("Found DEM file {}", file);
                        demFile = file;
                    } else {
                        LOG.info("Skipping DEM file {}", file);
                    }
                    break;
                case OTHER:
                    LOG.warn("Skipping unrecognized file '{}'", file);
            }
        }
        boolean hasOSM  = builderParams.streets && !osmFiles.isEmpty();
        boolean hasGTFS = builderParams.transit && !gtfsFiles.isEmpty();
        if ( ! ( hasOSM || hasGTFS )) {
            LOG.error("Found no input files from which to build a graph in {}", dir);
            return null;
        }
        if ( hasOSM ) {
            List<OpenStreetMapProvider> osmProviders = Lists.newArrayList();
            for (File osmFile : osmFiles) {
                OpenStreetMapProvider osmProvider = new AnyFileBasedOpenStreetMapProviderImpl(osmFile);
                osmProviders.add(osmProvider);
            }
            OpenStreetMapModule osmBuilder = new OpenStreetMapModule(osmProviders);
            DefaultStreetEdgeFactory streetEdgeFactory = new DefaultStreetEdgeFactory();
            streetEdgeFactory.useElevationData = builderParams.fetchElevationUS || (demFile != null);
            osmBuilder.edgeFactory = streetEdgeFactory;
            if( builderParams.osmProperties != null ) {

            } else {

            }
            DefaultWayPropertySetSource defaultWayPropertySetSource = new DefaultWayPropertySetSource();
            osmBuilder.setDefaultWayPropertySetSource(defaultWayPropertySetSource);

            osmBuilder.skipVisibility = !builderParams.areaVisibility;
            graphBuilder.addGraphBuilder(osmBuilder);
            graphBuilder.addGraphBuilder(new PruneFloatingIslands());
        }
        if ( hasGTFS ) {
            List<GtfsBundle> gtfsBundles = Lists.newArrayList();
            for (File gtfsFile : gtfsFiles) {
                GtfsBundle gtfsBundle = new GtfsBundle(gtfsFile);
                gtfsBundle.setTransfersTxtDefinesStationPaths(builderParams.useTransfersTxt);
                if (builderParams.parentStopLinking) {
                    gtfsBundle.linkStopsToParentStations = true;
                }
                gtfsBundle.parentStationTransfers = builderParams.stationTransfers;
                gtfsBundle.subwayAccessTime = (int)(builderParams.subwayAccessTime * 60);
                gtfsBundles.add(gtfsBundle);
            }
            GtfsModule gtfsBuilder = new GtfsModule(gtfsBundles);
            graphBuilder.addGraphBuilder(gtfsBuilder);
            if ( hasOSM ) {
                if (builderParams.matchBusRoutesToStreets) {
                    graphBuilder.addGraphBuilder(new BusRouteStreetMatcher());
                }
                graphBuilder.addGraphBuilder(new TransitToTaggedStopsModule());
                graphBuilder.addGraphBuilder(new TransitToStreetNetworkModule());
            }
            // The stops can be linked to each other once they are already linked to the street network.
            if ( ! builderParams.useTransfersTxt) {
                // This module will use streets or straight line distance depending on whether OSM data is found in the graph.
                graphBuilder.addGraphBuilder(new DirectTransferGenerator());
            }
            gtfsBuilder.setFareServiceFactory(new DefaultFareServiceFactory());
        }
        if (builderParams.fetchElevationUS) {
            File cacheDirectory = new File(params.cacheDirectory, "ned");
            ElevationGridCoverageFactory gcf = new NEDGridCoverageFactoryImpl(cacheDirectory);
            GraphBuilderModule elevationBuilder = new ElevationModule(gcf);
            graphBuilder.addGraphBuilder(elevationBuilder);
        } else  if (demFile != null) {
            ElevationGridCoverageFactory gcf = new GeotiffGridCoverageFactoryImpl(demFile);
            GraphBuilderModule elevationBuilder = new ElevationModule(gcf);
            graphBuilder.addGraphBuilder(elevationBuilder);
        }
        graphBuilder.addGraphBuilder(new EmbedConfig(builderConfig, routerConfig));
        if (builderParams.htmlAnnotations) {
            graphBuilder.addGraphBuilder(new AnnotationsToHTML(new File(params.build, "report.html")));
        }
        graphBuilder.serializeGraph = ( ! params.inMemory ) || params.preFlight;
        return graphBuilder;
    }

    /**
     * Represents the different types of files that might be present in a router / graph build directory.
     * We want to detect even those that are not graph builder inputs so we can effectively warn when unrecognized file
     * types are present. This helps point out when config files have been misnamed (builder-config vs. build-config).
     */
    private static enum InputFileType {
        GTFS, OSM, DEM, CONFIG, GRAPH, OTHER;
        public static InputFileType forFile(File file) {
            String name = file.getName();
            if (name.endsWith(".zip")) {
                try {
                    ZipFile zip = new ZipFile(file);
                    ZipEntry stopTimesEntry = zip.getEntry("stop_times.txt");
                    zip.close();
                    if (stopTimesEntry != null) return GTFS;
                } catch (Exception e) { /* fall through */ }
            }
            if (name.endsWith(".pbf")) return OSM;
            if (name.endsWith(".osm")) return OSM;
            if (name.endsWith(".osm.xml")) return OSM;
            if (name.endsWith(".tif")) return DEM; // Digital elevation model (elevation raster)
            if (name.equals("Graph.obj")) return GRAPH;
            if (name.equals(GraphBuilder.BUILDER_CONFIG_FILENAME) || name.equals(Router.ROUTER_CONFIG_FILENAME)) {
                return CONFIG;
            }
            return OTHER;
        }
    }

}

