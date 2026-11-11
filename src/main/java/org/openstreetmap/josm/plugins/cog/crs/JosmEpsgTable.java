package org.openstreetmap.josm.plugins.cog.crs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fallback on the projection definitions shipped by the host application:
 * JOSM embeds data/projection/custom-epsg (6000+ entries "<EPSG> +proj=... <>"
 * generated from the proj.4 epsg/esri tables plus the curated josm-epsg file).
 * The plugin reads that resource at runtime through the classloader chain - it
 * embeds and downloads no EPSG database of its own (proj4j-epsg stays banned,
 * see AGENTS.md); this is the host application's own projection data.
 *
 * The PROJ string is never fed to a library as-is: tokens are parsed, then the
 * parameters feed the plugin's own projection classes (geographic, transverse
 * Mercator, Lambert conformal conic) or, for the verified subset, the proj4j
 * backend - always with explicit +a/+b (never +ellps=, see Proj4Backend).
 * A code whose model is outside that whitelist stays rejected with an
 * explicit reason ({@link #reason}).
 *
 * Accuracy policy unchanged: the ellipsoid name table below carries the proj.4
 * legacy values, and every family and ellipsoid used is anchored against
 * GDAL/PROJ by the test suite (ProjectionTest). Datums are first order: the
 * towgs84/nadgrids tokens are ignored, like for the registered codes.
 */
final class JosmEpsgTable {

    private static final String RESOURCE = "/data/projection/custom-epsg";

    private static volatile Map<Integer, String> entries; // null = tried and unavailable

    private JosmEpsgTable() {
    }

    /** Projection for this EPSG code, or null (unknown code or unsupported model). */
    static Projection create(int epsg) {
        String def = lookup(epsg);
        if (def == null) {
            return null;
        }
        try {
            return build(epsg, def);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Why this code cannot be used (GUI error detail). Never null. */
    static String reason(int epsg) {
        Map<Integer, String> table = table();
        if (table == null) {
            return "the projection table shipped by the host application is unavailable";
        }
        String def = table.get(epsg);
        if (def == null) {
            return "the code is absent from the projection table shipped by the host application";
        }
        try {
            build(epsg, def);
            return "the entry resolved after all (call create)";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** Entry count, for diagnostics/tests; -1 when the resource is missing. */
    static synchronized int size() {
        Map<Integer, String> table = table();
        return table == null ? -1 : table.size();
    }

    private static String lookup(int epsg) {
        Map<Integer, String> table = table();
        return table == null ? null : table.get(epsg);
    }

    private static Map<Integer, String> table() {
        Map<Integer, String> e = entries;
        if (e == null) {
            synchronized (JosmEpsgTable.class) {
                e = entries;
                if (e == null) {
                    e = load();
                    entries = e == null ? NO_TABLE : e;
                }
            }
        }
        return e == NO_TABLE ? null : e;
    }

    /** Sentinel marking "resource already looked for and missing". */
    private static final Map<Integer, String> NO_TABLE = new HashMap<>();

    private static Map<Integer, String> load() {
        try (InputStream in = JosmEpsgTable.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return null;
            }
            Map<Integer, String> m = new HashMap<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) != '<' || !line.endsWith("<>")) {
                        continue; // comments and continuation lines
                    }
                    int end = line.indexOf('>');
                    if (end < 2) {
                        continue;
                    }
                    int code;
                    try {
                        code = Integer.parseInt(line.substring(1, end));
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    String def = line.substring(end + 1, line.length() - 2).trim();
                    if (!def.isEmpty() && !m.containsKey(code)) {
                        m.put(code, def); // first entry wins (JOSM order)
                    }
                }
            }
            return m;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------- building

    /**
     * Parses one entry and builds the projection. Package-visible for tests.
     *
     * @throws IllegalArgumentException when the entry carries a token the
     *         plugin cannot honour, or describes a model outside the whitelist
     */
    static Projection build(int epsg, String def) {
        Map<String, String> p = tokenize(def);
        String proj = p.get("proj");
        if (proj == null) {
            throw new IllegalArgumentException("table entry without +proj: [" + def + "]");
        }
        checkTokens(proj, p);
        double toMetre = linearFactor(p);
        Projection base;
        switch (proj) {
            case "lonlat":
            case "longlat":
                base = new Geographic(epsg, angle(p, "pm", 0.0, 180.0, false));
                break;
            case "utm":
                base = utm(epsg, p);
                break;
            case "tmerc":
                base = tmerc(epsg, p);
                break;
            case "lcc":
                base = lcc(epsg, p);
                break;
            case "aea":
            case "laea":
            case "stere":
                base = proj4j(proj, p);
                break;
            case "merc":
                throw new IllegalArgumentException(
                        "Mercator is not taken from the table: EPSG:3857 (Web Mercator)"
                        + " is built into the plugin as the display grid");
            default:
                throw new IllegalArgumentException("model +proj=" + proj
                        + " is not supported by the plugin");
        }
        return toMetre == 1.0 ? base : new Scaled(base, toMetre);
    }

    private static Map<String, String> tokenize(String def) {
        Map<String, String> p = new HashMap<>();
        for (String tok : def.trim().split("\\s+")) {
            if (!tok.startsWith("+") || tok.length() < 2) {
                throw new IllegalArgumentException("unparsable token [" + tok + "]");
            }
            String body = tok.substring(1);
            int eq = body.indexOf('=');
            String name = eq < 0 ? body : body.substring(0, eq);
            String value = eq < 0 ? "" : body.substring(eq + 1);
            if (p.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException("duplicated token [+" + name + "]");
            }
        }
        return p;
    }

    /** Tokens honoured everywhere (datum shift tokens carry no first-order role). */
    private static final Set<String> GLOBAL_TOKENS = tokens(
            "proj", "ellps", "datum", "a", "b", "rf", "towgs84", "nadgrids",
            "no_defs", "bounds", "units", "to_meter", "axis");

    /** Tokens per supported model (angles and linear offsets). */
    private static final Map<String, Set<String>> MODEL_TOKENS = new HashMap<>();
    static {
        MODEL_TOKENS.put("lonlat", tokens("pm"));
        MODEL_TOKENS.put("longlat", tokens("pm"));
        MODEL_TOKENS.put("utm", tokens("zone", "south"));
        MODEL_TOKENS.put("tmerc", tokens("lat_0", "lon_0", "k", "k_0", "x_0", "y_0"));
        MODEL_TOKENS.put("lcc", tokens("lat_0", "lat_1", "lat_2", "lon_0", "k", "k_0", "x_0", "y_0"));
        MODEL_TOKENS.put("aea", tokens("lat_0", "lat_1", "lat_2", "lon_0", "x_0", "y_0"));
        MODEL_TOKENS.put("laea", tokens("lat_0", "lon_0", "x_0", "y_0"));
        MODEL_TOKENS.put("stere", tokens("lat_0", "lon_0", "lat_ts", "k", "k_0", "x_0", "y_0"));
        // merc tokens exist only so that the entry reaches the explicit
        // "Mercator is not taken from the table" refusal of build()
        MODEL_TOKENS.put("merc", tokens("lat_0", "lon_0", "lat_ts", "k", "k_0", "x_0", "y_0"));
    }

    private static Set<String> tokens(String... names) {
        Set<String> s = new HashSet<>();
        for (String n : names) {
            s.add(n);
        }
        return s;
    }

    private static void checkTokens(String proj, Map<String, String> p) {
        Set<String> allowed = new HashSet<>(GLOBAL_TOKENS);
        Set<String> model = MODEL_TOKENS.get(proj);
        if (model == null) {
            throw new IllegalArgumentException("model +proj=" + proj
                    + " is not supported by the plugin");
        }
        allowed.addAll(model);
        for (String name : p.keySet()) {
            if (allowed.contains(name)) {
                continue;
            }
            if ("vunits".equals(name)) {
                throw new IllegalArgumentException("vertical CRS token +vunits is out of scope");
            }
            if ("pm".equals(name)) {
                throw new IllegalArgumentException(
                        "principal meridian +pm is only supported for geographic systems");
            }
            throw new IllegalArgumentException("token +" + name + " is not supported for +proj=" + proj);
        }
        String axis = p.get("axis");
        if (axis != null && !"neu".equals(axis)) {
            throw new IllegalArgumentException("axis order " + axis + " is not supported"
                    + " (only the standard east/north order)");
        }
    }

    /** Linear unit of the projected coordinates, metres per unit (like Scaled). */
    private static double linearFactor(Map<String, String> p) {
        String units = p.get("units");
        String toMetre = p.get("to_meter");
        if (units != null && toMetre != null) {
            throw new IllegalArgumentException("+units and +to_meter cannot be combined");
        }
        if (toMetre != null) {
            double f = num(toMetre);
            if (!(f > 0)) {
                throw new IllegalArgumentException("invalid +to_meter: " + toMetre);
            }
            return f;
        }
        if (units == null || "m".equals(units)) {
            return 1.0;
        }
        switch (units) {
            case "ft":
                return 0.3048;
            case "us-ft":
                return 1200.0 / 3937.0;
            default:
                throw new IllegalArgumentException("linear unit +units=" + units
                        + " is not supported (only m, ft, us-ft)");
        }
    }

    // ------------------------------------------------------------- models

    private static Projection utm(int epsg, Map<String, String> p) {
        int zone;
        try {
            zone = Integer.parseInt(p.get("zone"));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid +zone: " + p.get("zone"));
        }
        if (zone < 1 || zone > 60) {
            throw new IllegalArgumentException("UTM zone out of range: " + zone);
        }
        boolean south = p.containsKey("south");
        return new TransverseMercator(epsg, ellipsoid(p), 0.9996, 6.0 * zone - 183.0,
                0.0, 500000.0, south ? 10000000.0 : 0.0);
    }

    private static Projection tmerc(int epsg, Map<String, String> p) {
        double lat0 = angle(p, "lat_0", 0.0, 90.0, false);
        double lon0 = angle(p, "lon_0", 0.0, 540.0, false);
        return new TransverseMercator(epsg, ellipsoid(p), scaleFactor(p), lon0, lat0,
                length(p, "x_0"), length(p, "y_0"));
    }

    private static Projection lcc(int epsg, Map<String, String> p) {
        double sp1 = angle(p, "lat_1", 0.0, 90.0, true);
        double sp2 = angle(p, "lat_2", sp1, 90.0, false);
        double lat0 = angle(p, "lat_0", sp1, 90.0, false);
        double lon0 = angle(p, "lon_0", 0.0, 540.0, false);
        if (Math.abs(sp1) < 1e-12 && Math.abs(sp2) < 1e-12) {
            throw new IllegalArgumentException("null standard parallels (lat_1/lat_2)");
        }
        return new LambertConformalConic2SP(epsg, ellipsoid(p), sp1, sp2, lat0, lon0,
                length(p, "x_0"), length(p, "y_0"), p.containsKey("k") || p.containsKey("k_0")
                        ? scaleFactor(p) : null);
    }

    /**
     * Albers / Lambert azimuthal / oblique-polar stereographic: proj4j backend
     * (verified models, see {@link Proj4Backend}). The parameter string is
     * rebuilt from parsed values - never the raw entry text.
     */
    private static Projection proj4j(String proj, Map<String, String> p) {
        StringBuilder sb = new StringBuilder("+proj=").append(proj);
        double lon0 = angle(p, "lon_0", 0.0, 540.0, false);
        if ("aea".equals(proj)) {
            double sp1 = angle(p, "lat_1", 0.0, 90.0, true);
            sb.append(" +lat_1=").append(Proj4Backend.num(sp1));
            sb.append(" +lat_2=").append(Proj4Backend.num(angle(p, "lat_2", sp1, 90.0, false)));
            sb.append(" +lat_0=").append(Proj4Backend.num(angle(p, "lat_0", sp1, 90.0, false)));
            sb.append(" +lon_0=").append(Proj4Backend.num(lon0));
        } else if ("laea".equals(proj)) {
            sb.append(" +lat_0=").append(Proj4Backend.num(angle(p, "lat_0", 0.0, 90.0, true)));
            sb.append(" +lon_0=").append(Proj4Backend.num(lon0));
        } else {
            // stere: two verified forms (Proj4Backend), polar lat_ts or oblique k
            double lat0 = angle(p, "lat_0", 0.0, 90.0, true);
            sb.append(" +lat_0=").append(Proj4Backend.num(lat0));
            sb.append(" +lon_0=").append(Proj4Backend.num(lon0));
            String latTs = p.get("lat_ts");
            boolean hasK = p.containsKey("k") || p.containsKey("k_0");
            if (latTs != null) {
                double ts = angle(p, "lat_ts", 0.0, 90.0, false);
                if (Math.abs(lat0) < 90.0 - 1e-9) {
                    throw new IllegalArgumentException("stereographic +lat_ts with lat_0="
                            + lat0 + ": only the polar form (lat_0 = +-90) is verified");
                }
                if (Math.abs(ts) < 1e-12 || ts * lat0 < 0) {
                    throw new IllegalArgumentException("polar stereographic standard parallel"
                            + " +lat_ts=" + ts + ": inconsistent with the pole");
                }
                if (hasK && Math.abs(scaleFactor(p) - 1.0) > 1e-9) {
                    throw new IllegalArgumentException("polar stereographic scale factor with"
                            + " +lat_ts: only the k = 1 form is verified");
                }
                sb.append(" +lat_ts=").append(Proj4Backend.num(ts)).append(" +k_0=1");
            } else if (hasK) {
                // probe: at |lat_0| = 90 without lat_ts, proj4j and PROJ use
                // different radii (factor ~2); only the oblique k_0 form was
                // verified, the verified polar form is the lat_ts one above
                if (Math.abs(lat0) >= 90.0 - 1e-9) {
                    throw new IllegalArgumentException("polar stereographic without +lat_ts:"
                            + " the proj4j port does not reproduce PROJ for the"
                            + " k-only pole form");
                }
                sb.append(" +k_0=").append(Proj4Backend.num(scaleFactor(p)));
            } else {
                throw new IllegalArgumentException("stereographic entry without +lat_ts nor +k");
            }
        }
        sb.append(" +x_0=").append(Proj4Backend.num(length(p, "x_0")));
        sb.append(" +y_0=").append(Proj4Backend.num(length(p, "y_0")));
        Ellipsoid ell = ellipsoid(p);
        sb.append(" +a=").append(Proj4Backend.num(ell.semiMajorAxis));
        sb.append(" +b=").append(Proj4Backend.num(ell.semiMajorAxis * (1.0 - ell.flattening)));
        return Proj4Backend.fromTableDef(sb.toString(), ell);
    }

    private static double scaleFactor(Map<String, String> p) {
        String k = p.get("k");
        String k0 = p.get("k_0");
        if (k != null && k0 != null) {
            throw new IllegalArgumentException("+k and +k_0 cannot be combined");
        }
        double v = k != null ? num(k) : k0 != null ? num(k0) : 1.0;
        if (!(v > 0)) {
            throw new IllegalArgumentException("invalid scale factor: " + v);
        }
        return v;
    }

    private static double length(Map<String, String> p, String name) {
        String v = p.get(name);
        return v == null ? 0.0 : num(v);
    }

    // ------------------------------------------------------------ ellipsoid

    /**
     * proj.4 legacy ellipsoid names (values from the proj.4/PROJ tables, the
     * source the host file was generated from); each one used by a supported
     * entry is anchored against GDAL in ProjectionTest.
     */
    private static final Map<String, double[]> PROJ_ELLIPSOIDS = new HashMap<>();
    static {
        put("airy", 6377563.396, 299.3249646);
        put("mod_airy", 6377340.189, 299.3249646);
        put("aust_SA", 6378160.0, 298.25);
        put("bessel", 6377397.155, 299.1528128);
        put("bess_nam", 6377483.865, 299.1528128);
        put("clrk66", 6378206.4, 294.9786982);
        put("clrk80", 6378249.145, 293.4663);
        put("clrk80ign", 6378249.2, 293.4663077);
        put("evrst30", 6377276.345, 300.8017);
        put("evrst48", 6377304.063, 300.8017);
        put("evrst56", 6377301.243, 300.8017);
        put("evrst69", 6377295.664, 300.8017);
        put("evrstSS", 6377298.556, 300.8017);
        put("fschr60", 6378166.0, 298.3);
        put("fschr60m", 6378155.0, 298.3);
        put("fschr68", 6378150.0, 298.3);
        put("GRS67", 6378160.0, 298.247167427);
        put("GRS80", 6378137.0, 298.257222101);
        put("helmert", 6378200.0, 298.3); // PROJ >= 5 alias EPSG:7020 (anchored)
        put("hough", 6378270.0, 297.0);
        put("intl", 6378388.0, 297.0);
        put("krass", 6378245.0, 298.3);
        put("sphere", 6371000.0, 0.0);
        put("walbeck", 6376896.0, 299.0);
        put("WGS66", 6378145.0, 298.25);
        put("WGS72", 6378135.0, 298.26);
        put("WGS84", 6378137.0, 298.257223563);
    }

    private static void put(String name, double a, double rf) {
        PROJ_ELLIPSOIDS.put(name, new double[]{a, rf});
    }

    /** proj.4 legacy datum names: the ellipsoid they imply (no shift applied). */
    private static final Map<String, String> PROJ_DATUM_ELLIPSOIDS = new HashMap<>();
    static {
        PROJ_DATUM_ELLIPSOIDS.put("carthage", "clrk80ign");
        PROJ_DATUM_ELLIPSOIDS.put("GGRS87", "GRS80");
        PROJ_DATUM_ELLIPSOIDS.put("hermannskogel", "bessel");
        PROJ_DATUM_ELLIPSOIDS.put("ire65", "mod_airy");
        PROJ_DATUM_ELLIPSOIDS.put("NAD27", "clrk66");
        PROJ_DATUM_ELLIPSOIDS.put("NAD83", "GRS80");
        PROJ_DATUM_ELLIPSOIDS.put("nzgd49", "intl");
        PROJ_DATUM_ELLIPSOIDS.put("OSGB36", "airy");
        PROJ_DATUM_ELLIPSOIDS.put("potsdam", "bessel");
        PROJ_DATUM_ELLIPSOIDS.put("WGS84", "WGS84");
    }

    private static Ellipsoid ellipsoid(Map<String, String> p) {
        String name = p.get("ellps");
        String datum = p.get("datum");
        if (name != null && datum != null) {
            throw new IllegalArgumentException("+ellps and +datum cannot be combined");
        }
        if (name == null && datum != null) {
            name = PROJ_DATUM_ELLIPSOIDS.get(datum);
            if (name == null) {
                throw new IllegalArgumentException("unknown datum name +datum=" + datum);
            }
        }
        if (name != null) {
            if (p.containsKey("a") || p.containsKey("b") || p.containsKey("rf")) {
                throw new IllegalArgumentException("+ellps cannot be combined with a/b/rf");
            }
            double[] v = PROJ_ELLIPSOIDS.get(name);
            if (v == null) {
                throw new IllegalArgumentException("unknown ellipsoid name +ellps=" + name);
            }
            return Ellipsoid.of(v[0], v[1]);
        }
        String a = p.get("a");
        if (a == null) {
            if (p.containsKey("b") || p.containsKey("rf")) {
                throw new IllegalArgumentException("+b/+rf require +a");
            }
            throw new IllegalArgumentException("no ellipsoid (+ellps, +datum or +a) in the entry");
        }
        double aa = num(a);
        String b = p.get("b");
        String rf = p.get("rf");
        if (b != null && rf != null) {
            throw new IllegalArgumentException("+b and +rf cannot be combined");
        }
        if (b != null) {
            double bb = num(b);
            if (!(bb > 0) || bb > aa) {
                throw new IllegalArgumentException("invalid +b: " + b);
            }
            return Ellipsoid.of(aa, aa == bb ? 0.0 : aa / (aa - bb));
        }
        return Ellipsoid.of(aa, rf == null ? 0.0 : num(rf));
    }

    // -------------------------------------------------------- value parsing

    // decimal degrees, or PROJ DMS forms: 52d9'22.178", 0dE, -73d30'W, 45S
    // (the trailing " terminates the seconds, it introduces no value)
    private static final Pattern ANGLE = Pattern.compile(
            "^([+-]?)(\\d+(?:\\.\\d+)?)(?:d(\\d+(?:\\.\\d+)?)?)?(?:'(\\d+(?:\\.\\d+)?)?)?"
            + "\\\"?([NSEW])?$");

    private static double angle(Map<String, String> p, String name, double deflt,
            double limit, boolean required) {
        String token = p.get(name);
        if (token == null) {
            if (required) {
                throw new IllegalArgumentException("missing parameter +" + name);
            }
            return deflt;
        }
        Matcher m = ANGLE.matcher(token);
        if (!m.matches()) {
            throw new IllegalArgumentException("invalid angle +" + name + "=" + token);
        }
        double deg = Double.parseDouble(m.group(2));
        if (m.group(3) != null) {
            deg += Double.parseDouble(m.group(3)) / 60.0;
        }
        if (m.group(4) != null) {
            deg += Double.parseDouble(m.group(4)) / 3600.0;
        }
        String hemi = m.group(5);
        boolean negative = "-".equals(m.group(1)) || "S".equals(hemi) || "W".equals(hemi);
        double v = negative ? -deg : deg;
        if (!(v >= -limit && v <= limit)) {
            throw new IllegalArgumentException("angle out of range +" + name + "=" + token);
        }
        return v;
    }

    private static double num(String token) {
        double v = Double.parseDouble(token);
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException("invalid number: " + token);
        }
        return v;
    }

    /** Adapter scaling the projection output from the entry's linear unit. */
    private static final class Scaled implements Projection {

        private final Projection base;
        private final double toMetre;

        Scaled(Projection base, double toMetre) {
            this.base = base;
            this.toMetre = toMetre;
        }

        @Override
        public double[] project(double lat, double lon) {
            double[] xy = base.project(lat, lon);
            return new double[]{xy[0] / toMetre, xy[1] / toMetre};
        }

        @Override
        public double[] unproject(double x, double y) {
            return base.unproject(x * toMetre, y * toMetre);
        }

        @Override
        public int epsg() {
            return base.epsg();
        }
    }
}
