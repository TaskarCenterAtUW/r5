package com.conveyal.r5.osw;

import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.trove.AugmentedList;
import com.conveyal.r5.trove.TByteAugmentedList;
import com.conveyal.r5.trove.TDoubleAugmentedList;
import com.conveyal.r5.trove.TIntAugmentedList;
import gnu.trove.list.TByteList;
import gnu.trove.list.TDoubleList;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TByteArrayList;
import gnu.trove.list.array.TDoubleArrayList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntLongMap;
import gnu.trove.map.hash.TIntLongHashMap;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenSidewalks (OSW) attributes of street edges, stored alongside the EdgeStore so that pedestrian cost profiles
 * (see {@link PedestrianCostProfile}) can be evaluated per edge at routing time.
 *
 * Like geometries and lengths in the EdgeStore, these values are stored once per edge PAIR. Direction-dependent
 * values (incline) are stored in the sense of the forward edge and must be negated for the backward edge.
 *
 * The tags of an edge, minus the per-edge numeric/identifier keys listed in {@link #PER_EDGE_KEYS}, are interned into
 * a small table of distinct tag sets so that matching profile rules against tags is cheap and memory use stays low.
 *
 * Edge pairs not derived from OSW edges (transit links, scenario-added streets) have tag set index -1.
 */
public class OswEdgeAttributes implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Curb ramp status of a crossing, derived from OSW kerb nodes or an explicit "curbramps" edge tag. */
    public static final byte CURB_RAMPS_UNKNOWN = 0;
    public static final byte CURB_RAMPS_YES = 1;
    public static final byte CURB_RAMPS_NO = 2;

    /** Tag keys that vary per edge and are stored in dedicated columns or not needed for rule matching. */
    public static final Set<String> PER_EDGE_KEYS = Set.of("_id", "_u_id", "_v_id", "_u", "_v", "length", "incline");

    /**
     * Keys that profiles may not match on: the per-edge keys above, plus keys that only exist in one engine's view of
     * an edge (curb ramps are derived from kerb nodes in R5; Unweaver adds _layer, geom and fid). Matching on these
     * would make R5 and Unweaver disagree. Mirrored in osw-tools/pedestrian_profile.py.
     */
    public static final Set<String> RESERVED_MATCH_KEYS = Set.of("_id", "_u_id", "_v_id", "_u", "_v", "length",
            "incline", "incline_text", "curbramps", "_layer", "geom", "fid");

    /** Index into tagSets for each edge pair, or -1 if the pair has no OSW attributes. */
    private TIntList tagSetIndex = new TIntArrayList();

    /** Incline as a grade (0.05 = 5% uphill) in the direction of the forward edge. NaN if absent or non-numeric. */
    private TDoubleList incline = new TDoubleArrayList();

    /** The OSW "length" property in meters, NaN if absent. */
    private TDoubleList lengthMeters = new TDoubleArrayList();

    /** One of the CURB_RAMPS_* constants. */
    private TByteList curbRamps = new TByteArrayList();

    /** Distinct tag sets. Grows only during network build; treated as immutable afterward. */
    private List<Map<String, String>> tagSets = new ArrayList<>();

    /** Reverse index for interning tag sets during build. Not needed after build. */
    private transient Map<Map<String, String>, Integer> tagSetIds;

    /** Original OSW identifiers for the edges and nodes, which R5 replaces with numeric IDs. */
    public OswIdMap ids = new OswIdMap();

    /** The numeric OSW node ID of each street vertex created from an OSW node (see ids to recover string IDs). */
    public TIntLongMap nodeIdForVertex = new TIntLongHashMap(10_000, 0.5f, -1, -1);

    /** @return the original OSW node ID of the given vertex, or null if it does not correspond to an OSW node. */
    public String oswNodeId (int vertex) {
        long id = nodeIdForVertex.get(vertex);
        return id == -1 ? null : ids.nodeId(id);
    }

    private int nPairs = 0;

    /** Evaluated cost profiles by runSpecId, so each spec is evaluated over the network only once. LRU, small. */
    private transient Map<String, PedestrianCostTable> costTables;

    private static final int MAX_CACHED_COST_TABLES = 8;

    public int nPairs () {
        return nPairs;
    }

    /** Add a pair with no OSW attributes. Must be called once for every edge pair added to the EdgeStore. */
    public void addNeutralPair () {
        tagSetIndex.add(-1);
        incline.add(Double.NaN);
        lengthMeters.add(Double.NaN);
        curbRamps.add(CURB_RAMPS_UNKNOWN);
        nPairs += 1;
    }

    /** Set the OSW attributes of an existing edge pair. */
    public void setPair (int pairIndex, Map<String, String> tags, double forwardIncline, double lengthM, byte curbRampStatus) {
        Map<String, String> filtered = new HashMap<>();
        tags.forEach((k, v) -> {
            if (!PER_EDGE_KEYS.contains(k)) filtered.put(k, v);
        });
        tagSetIndex.set(pairIndex, internTagSet(filtered));
        incline.set(pairIndex, forwardIncline);
        lengthMeters.set(pairIndex, lengthM);
        curbRamps.set(pairIndex, curbRampStatus);
    }

    /** Copy all attributes of one pair to another, e.g. when an edge is split to link a stop or origin. */
    public void copyPair (int fromPairIndex, int toPairIndex) {
        tagSetIndex.set(toPairIndex, tagSetIndex.get(fromPairIndex));
        incline.set(toPairIndex, incline.get(fromPairIndex));
        lengthMeters.set(toPairIndex, lengthMeters.get(fromPairIndex));
        curbRamps.set(toPairIndex, curbRamps.get(fromPairIndex));
    }

    private int internTagSet (Map<String, String> tags) {
        if (tagSetIds == null) {
            tagSetIds = new HashMap<>();
            for (int i = 0; i < tagSets.size(); i++) tagSetIds.put(tagSets.get(i), i);
        }
        Integer existing = tagSetIds.get(tags);
        if (existing != null) return existing;
        int id = tagSets.size();
        // Stored as plain HashMaps (not unmodifiable wrappers) so that Kryo can deserialize them.
        Map<String, String> copy = new HashMap<>(tags);
        tagSets.add(copy);
        tagSetIds.put(copy, id);
        return id;
    }

    /**
     * Interpret an explicit curbramps value: true/yes/1 (or 1.0), false/no/0 (or 0.0). Anything else is unknown, in
     * which case R5 derives the status from kerb nodes. Mirrored in osw-tools (pedestrian_profile.parse_curb_ramps).
     */
    public static byte parseCurbRamps (String value) {
        if (value == null) return CURB_RAMPS_UNKNOWN;
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.equals("true") || v.equals("yes")) return CURB_RAMPS_YES;
        if (v.equals("false") || v.equals("no")) return CURB_RAMPS_NO;
        try {
            double d = Double.parseDouble(v);
            if (d == 1) return CURB_RAMPS_YES;
            if (d == 0) return CURB_RAMPS_NO;
        } catch (NumberFormatException e) {
            // Not numeric
        }
        return CURB_RAMPS_UNKNOWN;
    }

    public boolean hasAttributes (int edgeIndex) {
        int pair = edgeIndex / 2;
        return pair < nPairs && tagSetIndex.get(pair) >= 0;
    }

    /**
     * @return the tags of the given (directed) edge's pair, or null if it has no OSW attributes. The map is shared
     *         between all edges with the same tags and must not be modified.
     */
    public Map<String, String> tags (int edgeIndex) {
        int pair = edgeIndex / 2;
        if (pair >= nPairs) return null;
        int t = tagSetIndex.get(pair);
        return t < 0 ? null : tagSets.get(t);
    }

    /** @return the incline in the direction of travel along the given directed edge, NaN if unknown. */
    public double incline (int edgeIndex) {
        int pair = edgeIndex / 2;
        if (pair >= nPairs) return Double.NaN;
        double i = incline.get(pair);
        return (edgeIndex % 2 == 0) ? i : -i;
    }

    public double lengthMeters (int edgeIndex) {
        int pair = edgeIndex / 2;
        return pair < nPairs ? lengthMeters.get(pair) : Double.NaN;
    }

    public byte curbRamps (int edgeIndex) {
        int pair = edgeIndex / 2;
        return pair < nPairs ? curbRamps.get(pair) : CURB_RAMPS_UNKNOWN;
    }

    public int nTagSets () {
        return tagSets.size();
    }

    /**
     * @return per-edge results of evaluating the given spec over the given EdgeStore, which must be the one holding
     *         these attributes. Cached by runSpecId.
     */
    public synchronized PedestrianCostTable costTable (PedestrianCostSpec spec, EdgeStore edgeStore) {
        if (edgeStore.oswAttributes != this) {
            throw new IllegalArgumentException("Edge store does not hold these OSW attributes.");
        }
        if (costTables == null) {
            costTables = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry (Map.Entry<String, PedestrianCostTable> eldest) {
                    return size() > MAX_CACHED_COST_TABLES;
                }
            };
        }
        return costTables.computeIfAbsent(spec.runSpecId, id -> new PedestrianCostTable(spec, edgeStore));
    }

    /** Make a copy for a scenario, sharing the baseline values and only allowing additions and changes to new pairs. */
    public OswEdgeAttributes extendOnlyCopy () {
        OswEdgeAttributes copy = new OswEdgeAttributes();
        copy.tagSetIndex = new TIntAugmentedList(tagSetIndex);
        copy.incline = new TDoubleAugmentedList(incline);
        copy.lengthMeters = new TDoubleAugmentedList(lengthMeters);
        copy.curbRamps = new TByteAugmentedList(curbRamps);
        // Scenarios only copy existing tag sets onto new pairs, so the table can be shared.
        copy.tagSets = new AugmentedList<>(tagSets);
        copy.ids = ids;
        // Scenarios do not create vertices from OSW nodes, so the baseline mapping is shared (read-only).
        copy.nodeIdForVertex = nodeIdForVertex;
        copy.nPairs = nPairs;
        return copy;
    }

    /**
     * OSW identifiers are strings. R5 requires numeric OSM-style IDs, so numeric OSW IDs are used as-is and other IDs
     * are assigned sequential numbers. This records the mapping so results can be reported using the original IDs.
     */
    public static class OswIdMap implements Serializable {
        private static final long serialVersionUID = 1L;
        /** Only populated for IDs that were not numeric. */
        public final Map<Long, String> nodeIds = new HashMap<>();
        public final Map<Long, String> edgeIds = new HashMap<>();

        public String nodeId (long numericId) {
            String s = nodeIds.get(numericId);
            return s != null ? s : Long.toString(numericId);
        }

        public String edgeId (long numericId) {
            String s = edgeIds.get(numericId);
            return s != null ? s : Long.toString(numericId);
        }
    }
}
