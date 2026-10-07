package com.conveyal.r5.osw;

import com.conveyal.osmlib.Node;
import com.conveyal.osmlib.OSM;
import com.conveyal.osmlib.OSMEntity;
import com.conveyal.osmlib.Way;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads an OpenSidewalks (OSW 0.2) dataset into an osm-lib {@link OSM} so that R5's normal street network builder can
 * consume it. This avoids converting OSW to OSM PBF and preserves OSW node and edge identities.
 *
 * Mapping:
 *  - Each OSW node becomes an OSM node whose tags are the node's properties (including barrier=kerb / kerb=*).
 *  - Each OSW edge becomes exactly one OSM way running _u_id -> (interior geometry points) -> _v_id, with the edge's
 *    properties as tags. Interior geometry points become untagged nodes with negative IDs. Because OSW edges only
 *    meet at their end nodes, R5 will produce exactly one edge pair per OSW edge.
 *  - Points, lines, polygons and zones are ignored.
 *
 * Input may be a .zip file or a directory containing *.nodes.geojson and *.edges.geojson, or the path to a single
 * *.edges.geojson file (its sibling *.nodes.geojson is used if present). Names are matched case-insensitively and
 * may carry an ".OSW" infix, as in the schema repository's example data (wa.microsoft.graph.edges.OSW.geojson).
 */
public class OswReader {

    private static final Logger LOG = LoggerFactory.getLogger(OswReader.class);

    /** Assigned (non-numeric) OSW IDs are numbered from here, well above typical numeric OSW/OSM IDs. */
    private static final long ASSIGNED_ID_BASE = 1L << 52;

    /** Same precision Unweaver uses to derive node IDs from coordinates when no node IDs are given. */
    private static final int COORDINATE_KEY_PRECISION = 7;

    private final ObjectMapper mapper = new ObjectMapper();

    private final OSM osm;

    private final OswEdgeAttributes.OswIdMap ids = new OswEdgeAttributes.OswIdMap();

    private final Map<String, Long> nodeIdLookup = new HashMap<>();
    private final Map<String, Long> edgeIdLookup = new HashMap<>();

    private long nextAssignedNodeId = ASSIGNED_ID_BASE;
    private long nextAssignedEdgeId = ASSIGNED_ID_BASE;
    private long nextInteriorNodeId = -1;

    private int nNodes, nEdges, nSkippedEdges;

    private OswReader (OSM osm) {
        this.osm = osm;
    }

    /** Result of reading an OSW dataset: the OSM data plus the mapping back to OSW identifiers. */
    public static class Result {
        public final OSM osm;
        public final OswEdgeAttributes.OswIdMap ids;
        Result (OSM osm, OswEdgeAttributes.OswIdMap ids) {
            this.osm = osm;
            this.ids = ids;
        }
    }

    /** Read the OSW dataset at the given path into a new in-memory OSM with intersection detection enabled. */
    public static Result read (String path) {
        OSM osm = OSM.newWritableInMemory();
        osm.intersectionDetection = true;
        OswReader reader = new OswReader(osm);
        try {
            reader.readPath(new File(path));
        } catch (IOException e) {
            throw new RuntimeException("Could not read OSW dataset " + path, e);
        }
        LOG.info("Read OSW dataset {}: {} nodes, {} edges ({} skipped).", path, reader.nNodes, reader.nEdges, reader.nSkippedEdges);
        return new Result(osm, reader.ids);
    }

    /** @return true if the path looks like an OSW dataset rather than an OSM file. */
    public static boolean isOswPath (String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".osw") || p.endsWith(".osw.zip") || isEdgesFile(p)) return true;
        File f = new File(path);
        if (f.isDirectory()) {
            File[] edges = f.listFiles((d, name) -> isEdgesFile(name));
            return edges != null && edges.length > 0;
        }
        if (p.endsWith(".zip") && f.exists()) {
            try (ZipFile zip = new ZipFile(f)) {
                return zip.stream().anyMatch(e -> isEdgesFile(e.getName()));
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    private void readPath (File file) throws IOException {
        if (file.isDirectory()) {
            File nodes = null, edges = null;
            for (File f : file.listFiles()) {
                if (isNodesFile(f.getName())) nodes = f;
                if (isEdgesFile(f.getName())) edges = f;
            }
            readFiles(nodes, edges);
        } else if (isEdgesFile(file.getName())) {
            // The sibling nodes file has the same name with "edges" replaced by "nodes".
            String name = file.getName();
            int i = name.toLowerCase(Locale.ROOT).lastIndexOf(".edges.");
            File nodes = new File(file.getParentFile(), name.substring(0, i) + ".nodes." + name.substring(i + 7));
            readFiles(nodes.exists() ? nodes : null, file);
        } else {
            // Treat anything else (.zip, .osw) as a zip archive.
            try (ZipFile zip = new ZipFile(file)) {
                ZipEntry nodes = null, edges = null;
                for (Iterator<? extends ZipEntry> it = zip.entries().asIterator(); it.hasNext(); ) {
                    ZipEntry e = it.next();
                    if (e.getName().contains("__MACOSX")) continue;
                    if (isNodesFile(e.getName())) nodes = e;
                    if (isEdgesFile(e.getName())) edges = e;
                }
                if (edges == null) throw new IllegalArgumentException("No *.edges.geojson in OSW archive " + file);
                if (nodes != null) {
                    try (InputStream in = zip.getInputStream(nodes)) {
                        forEachFeature(in, this::readNode);
                    }
                }
                try (InputStream in = zip.getInputStream(edges)) {
                    forEachFeature(in, this::readEdge);
                }
            }
        }
    }

    /** @return a readable name for a dataset: its edges file name without the ".edges..." suffix, if one is found. */
    public static String datasetName (String path) {
        File f = new File(path);
        String edges = null;
        if (f.isDirectory()) {
            File[] files = f.listFiles((d, n) -> isEdgesFile(n));
            if (files != null && files.length > 0) edges = files[0].getName();
        } else if (isEdgesFile(f.getName())) {
            edges = f.getName();
        } else {
            try (ZipFile zip = new ZipFile(f)) {
                edges = zip.stream().map(ZipEntry::getName).filter(OswReader::isEdgesFile)
                        .map(n -> new File(n).getName()).findFirst().orElse(null);
            } catch (IOException e) {
                // Not a zip; fall back to the file name.
            }
        }
        if (edges == null) return f.getName();
        return edges.substring(0, edges.toLowerCase(Locale.ROOT).lastIndexOf(".edges."));
    }

    /** Matches *.edges.geojson and *.edges.OSW.geojson, ignoring case. */
    static boolean isEdgesFile (String name) {
        return name.toLowerCase(Locale.ROOT).matches(".*\\.edges(\\.osw)?\\.geojson");
    }

    static boolean isNodesFile (String name) {
        return name.toLowerCase(Locale.ROOT).matches(".*\\.nodes(\\.osw)?\\.geojson");
    }

    private void readFiles (File nodes, File edges) throws IOException {
        if (edges == null) throw new IllegalArgumentException("No *.edges.geojson found.");
        if (nodes != null) {
            try (InputStream in = new FileInputStream(nodes)) {
                forEachFeature(in, this::readNode);
            }
        } else {
            LOG.warn("No OSW nodes file found. Node tags (e.g. kerbs) will be unavailable.");
        }
        try (InputStream in = new FileInputStream(edges)) {
            forEachFeature(in, this::readEdge);
        }
    }

    /** Stream the features of a GeoJSON FeatureCollection without loading the whole file into memory. */
    private void forEachFeature (InputStream in, Consumer<JsonNode> handler) throws IOException {
        JsonParser parser = new JsonFactory(mapper).createParser(in);
        if (parser.nextToken() != JsonToken.START_OBJECT) throw new IOException("GeoJSON must be an object.");
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.getCurrentName();
            JsonToken t = parser.nextToken();
            if ("features".equals(field) && t == JsonToken.START_ARRAY) {
                while (parser.nextToken() == JsonToken.START_OBJECT) {
                    handler.accept(parser.readValueAsTree());
                }
            } else {
                parser.skipChildren();
            }
        }
    }

    private void readNode (JsonNode feature) {
        JsonNode geom = feature.get("geometry");
        JsonNode props = feature.get("properties");
        if (geom == null || !"Point".equals(geom.path("type").asText()) || props == null) return;
        JsonNode coords = geom.get("coordinates");
        String oswId = props.path("_id").asText(null);
        long id = oswId != null ? nodeId(oswId) : nodeIdForCoordinate(coords.get(0).asDouble(), coords.get(1).asDouble());
        Node node = new Node(coords.get(1).asDouble(), coords.get(0).asDouble());
        node.tags = tagsFromProperties(props);
        osm.writeNode(id, node);
        nNodes++;
    }

    private void readEdge (JsonNode feature) {
        JsonNode geom = feature.get("geometry");
        JsonNode props = feature.get("properties");
        if (geom == null || !"LineString".equals(geom.path("type").asText()) || props == null) {
            nSkippedEdges++;
            return;
        }
        JsonNode coords = geom.get("coordinates");
        int n = coords.size();
        if (n < 2) {
            nSkippedEdges++;
            return;
        }
        long u = endNode(props.path("_u_id").asText(null), coords.get(0));
        long v = endNode(props.path("_v_id").asText(null), coords.get(n - 1));
        if (u == v && n == 2) {
            nSkippedEdges++;
            return;
        }
        long[] nodes = new long[n];
        nodes[0] = u;
        nodes[n - 1] = v;
        for (int i = 1; i < n - 1; i++) {
            long interior = nextInteriorNodeId--;
            osm.writeNode(interior, new Node(coords.get(i).get(1).asDouble(), coords.get(i).get(0).asDouble()));
            nodes[i] = interior;
        }
        Way way = new Way();
        way.nodes = nodes;
        way.tags = tagsFromProperties(props);
        if (!props.hasNonNull("length")) {
            // OSW lengths are haversine lengths in meters; fill in missing ones exactly as
            // osw-tools/prepare_osw_for_unweaver.py does, so both engines use the same length.
            way.tags.add(new OSMEntity.Tag("length", Double.toString(Math.round(haversineMeters(coords) * 100) / 100.0)));
        }
        String oswId = props.path("_id").asText(null);
        long wayId = oswId != null ? edgeId(oswId) : nextAssignedEdgeId++;
        osm.writeWay(wayId, way);
        nEdges++;
    }

    /** Get the ID for an edge's end node, creating the node from the edge geometry if the nodes file lacks it. */
    private long endNode (String oswId, JsonNode coordinate) {
        double lon = coordinate.get(0).asDouble();
        double lat = coordinate.get(1).asDouble();
        long id = oswId != null ? nodeId(oswId) : nodeIdForCoordinate(lon, lat);
        if (!osm.nodes.containsKey(id)) {
            osm.writeNode(id, new Node(lat, lon));
        }
        return id;
    }

    private long nodeId (String oswId) {
        Long existing = nodeIdLookup.get(oswId);
        if (existing != null) return existing;
        long id = parsePositiveLong(oswId);
        if (id < 0) {
            id = nextAssignedNodeId++;
            ids.nodeIds.put(id, oswId);
        }
        nodeIdLookup.put(oswId, id);
        return id;
    }

    private long edgeId (String oswId) {
        Long existing = edgeIdLookup.get(oswId);
        if (existing != null) {
            // Duplicate edge IDs: keep both edges, but give the second a new number.
            long id = nextAssignedEdgeId++;
            ids.edgeIds.put(id, oswId);
            return id;
        }
        long id = parsePositiveLong(oswId);
        if (id < 0) {
            id = nextAssignedEdgeId++;
            ids.edgeIds.put(id, oswId);
        }
        edgeIdLookup.put(oswId, id);
        return id;
    }

    /** Unweaver's convention for node IDs when none are supplied: "lon, lat" rounded to 7 decimal places. */
    private long nodeIdForCoordinate (double lon, double lat) {
        return nodeId(coordinateKey(lon, lat));
    }

    public static String coordinateKey (double lon, double lat) {
        double scale = Math.pow(10, COORDINATE_KEY_PRECISION);
        return (Math.round(lon * scale) / scale) + ", " + (Math.round(lat * scale) / scale);
    }

    /** @return the ID as a number if it is a canonical positive integer (so "007" and "7" stay distinct), else -1. */
    private static long parsePositiveLong (String s) {
        try {
            long l = Long.parseLong(s);
            return (l > 0 && l < ASSIGNED_ID_BASE && Long.toString(l).equals(s)) ? l : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static double haversineMeters (JsonNode coords) {
        final double r = 6371008.8;
        double total = 0;
        for (int i = 1; i < coords.size(); i++) {
            double lon1 = coords.get(i - 1).get(0).asDouble(), lat1 = coords.get(i - 1).get(1).asDouble();
            double lon2 = coords.get(i).get(0).asDouble(), lat2 = coords.get(i).get(1).asDouble();
            double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
            double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
            double a = Math.pow(Math.sin(dp / 2), 2) + Math.cos(p1) * Math.cos(p2) * Math.pow(Math.sin(dl / 2), 2);
            total += 2 * r * Math.asin(Math.sqrt(a));
        }
        return total;
    }

    private static List<OSMEntity.Tag> tagsFromProperties (JsonNode props) {
        List<OSMEntity.Tag> tags = new ArrayList<>(props.size());
        for (Iterator<Map.Entry<String, JsonNode>> it = props.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode value = e.getValue();
            if (value == null || value.isNull()) continue;
            String text = value.isValueNode() ? value.asText() : value.toString();
            tags.add(new OSMEntity.Tag(e.getKey(), text));
        }
        return tags;
    }
}
