package pl.lighty.launcher;

import com.google.gson.*;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Logika launchera: pobieranie, instalacja loaderów (Vanilla/Fabric/Forge/Quilt) i start gry. */
public class Core {

    public interface Listener {
        void log(String msg);
        void progress(double fraction); // 0.0 - 1.0
    }

    public record Options(String username, String mc, String loader, int ramMb, String java) {}

    static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    static final String RESOURCES = "https://resources.download.minecraft.net/";
    static final String LIBRARIES = "https://libraries.minecraft.net/";
    static final String OS = detectOs();

    final Path root = defaultRoot();
    final Path versions = root.resolve("versions");
    final Path libs = root.resolve("libraries");
    final Path assets = root.resolve("assets");
    final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    final Listener ui;

    public Core(Listener ui) {
        this.ui = ui;
    }

    // ------------------------------------------------------------------ system

    static String detectOs() {
        String o = System.getProperty("os.name").toLowerCase();
        if (o.contains("win")) return "windows";
        if (o.contains("mac")) return "osx";
        return "linux";
    }

    static boolean is64() {
        return System.getProperty("os.arch").contains("64");
    }

    static String arch() {
        String a = System.getProperty("os.arch").toLowerCase();
        if (a.contains("aarch64") || a.contains("arm64")) return "arm64";
        return is64() ? "x86_64" : "x86";
    }

    static Path defaultRoot() {
        String home = System.getProperty("user.home");
        if (OS.equals("windows") && System.getenv("APPDATA") != null)
            return Path.of(System.getenv("APPDATA"), ".lightylauncher");
        if (OS.equals("osx")) return Path.of(home, "Library", "Application Support", "lightylauncher");
        return Path.of(home, ".lightylauncher");
    }

    static String defaultJava() {
        return Path.of(System.getProperty("java.home"), "bin", OS.equals("windows") ? "java.exe" : "java").toString();
    }

    public Path instanceDir(String mc, String loader) {
        return root.resolve("instances").resolve(mc + "-" + loader.toLowerCase());
    }

    // ------------------------------------------------------------------ sieć

    String getString(String url) throws IOException, InterruptedException {
        HttpRequest rq = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "LightyLauncher/1.0").build();
        HttpResponse<String> r = http.send(rq, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " dla " + url);
        return r.body();
    }

    JsonElement getJson(String url) throws IOException, InterruptedException {
        return JsonParser.parseString(getString(url));
    }

    static String sha1Of(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    void download(String url, Path target, String sha1) throws IOException, InterruptedException {
        if (Files.exists(target) && (sha1 == null || sha1.equalsIgnoreCase(sha1Of(target)))) return;
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".part");
        HttpRequest rq = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "LightyLauncher/1.0").build();
        HttpResponse<Path> r = http.send(rq, HttpResponse.BodyHandlers.ofFile(tmp));
        if (r.statusCode() != 200) {
            Files.deleteIfExists(tmp);
            throw new IOException("HTTP " + r.statusCode() + " dla " + url);
        }
        if (sha1 != null && !sha1.equalsIgnoreCase(sha1Of(tmp))) {
            Files.deleteIfExists(tmp);
            throw new IOException("Błędna suma SHA-1: " + url);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    static JsonObject readJson(Path p) throws IOException {
        return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
    }

    // ------------------------------------------------------------------ wersje

    public List<String> listVersions(boolean snapshots) throws Exception {
        List<String> out = new ArrayList<>();
        for (JsonElement e : getJson(MANIFEST).getAsJsonObject().getAsJsonArray("versions")) {
            JsonObject v = e.getAsJsonObject();
            String type = v.get("type").getAsString();
            if (type.equals("release") || (snapshots && type.equals("snapshot")))
                out.add(v.get("id").getAsString());
        }
        return out;
    }

    void ensureVanillaJson(String mc) throws Exception {
        Path json = versions.resolve(mc).resolve(mc + ".json");
        if (Files.exists(json)) return;
        for (JsonElement e : getJson(MANIFEST).getAsJsonObject().getAsJsonArray("versions")) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("id").getAsString().equals(mc)) {
                download(o.get("url").getAsString(), json, null);
                return;
            }
        }
        throw new IOException("Nie znaleziono wersji " + mc);
    }

    Path baseJar(String mc) throws Exception {
        JsonObject v = readJson(versions.resolve(mc).resolve(mc + ".json"));
        JsonObject c = v.getAsJsonObject("downloads").getAsJsonObject("client");
        Path jar = versions.resolve(mc).resolve(mc + ".jar");
        download(c.get("url").getAsString(), jar, c.get("sha1").getAsString());
        return jar;
    }

    // ------------------------------------------------------------------ loadery

    /** Fabric i Quilt: oba udostępniają gotowy profil JSON. */
    String installProfileLoader(String base, String mc) throws Exception {
        JsonArray arr = getJson(base + mc).getAsJsonArray();
        if (arr.size() == 0) throw new IOException("Ten loader nie wspiera wersji " + mc);
        String lv = null;
        for (JsonElement e : arr) {
            String v = e.getAsJsonObject().getAsJsonObject("loader").get("version").getAsString();
            if (!v.contains("-")) { lv = v; break; } // pomijamy beta/rc
        }
        if (lv == null) lv = arr.get(0).getAsJsonObject().getAsJsonObject("loader").get("version").getAsString();
        String prof = getString(base + mc + "/" + lv + "/profile/json");
        String id = JsonParser.parseString(prof).getAsJsonObject().get("id").getAsString();
        Path p = versions.resolve(id).resolve(id + ".json");
        if (!Files.exists(p)) {
            Files.createDirectories(p.getParent());
            Files.writeString(p, prof);
        }
        return id;
    }

    String findForgeId(String mc, String fv) throws IOException {
        if (!Files.isDirectory(versions)) return null;
        try (Stream<Path> s = Files.list(versions)) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(mc) && n.toLowerCase().contains("forge") && n.contains(fv))
                    .filter(n -> Files.exists(versions.resolve(n).resolve(n + ".json")))
                    .findFirst().orElse(null);
        }
    }

    /** Forge: pobieramy oficjalny installer i uruchamiamy go w trybie --installClient. */
    String installForge(String mc, String javaExe) throws Exception {
        JsonObject promos = getJson("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json")
                .getAsJsonObject().getAsJsonObject("promos");
        String fv;
        if (promos.has(mc + "-recommended")) fv = promos.get(mc + "-recommended").getAsString();
        else if (promos.has(mc + "-latest")) fv = promos.get(mc + "-latest").getAsString();
        else throw new IOException("Forge nie wspiera wersji " + mc);

        String existing = findForgeId(mc, fv);
        if (existing != null) return existing;

        ui.log("Instaluję Forge " + fv + " (to chwilę potrwa)...");
        Path installer = root.resolve("cache").resolve("forge-" + mc + "-" + fv + "-installer.jar");
        download("https://maven.minecraftforge.net/net/minecraftforge/forge/" + mc + "-" + fv
                + "/forge-" + mc + "-" + fv + "-installer.jar", installer, null);

        Path lp = root.resolve("launcher_profiles.json"); // installer wymaga tego pliku
        if (!Files.exists(lp)) Files.writeString(lp, "{\"profiles\":{}}");

        Process p = new ProcessBuilder(javaExe, "-jar", installer.toString(), "--installClient", root.toString())
                .redirectErrorStream(true).start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l;
            while ((l = r.readLine()) != null) ui.log("[forge] " + l);
        }
        if (p.waitFor() != 0) throw new IOException("Installer Forge zakończył się błędem");
        existing = findForgeId(mc, fv);
        if (existing == null) throw new IOException("Nie znaleziono zainstalowanej wersji Forge");
        return existing;
    }

    // ------------------------------------------------------------------ JSON wersji

    /** Scala wersję z "inheritsFrom" (Fabric/Quilt/Forge dziedziczą po vanilli). */
    JsonObject resolve(String id) throws IOException {
        JsonObject v = readJson(versions.resolve(id).resolve(id + ".json"));
        if (!v.has("inheritsFrom")) {
            v.addProperty("_base", id);
            return v;
        }
        JsonObject p = resolve(v.get("inheritsFrom").getAsString());
        JsonObject r = p.deepCopy();
        for (Map.Entry<String, JsonElement> en : v.entrySet()) {
            if (en.getKey().equals("libraries") || en.getKey().equals("arguments")) continue;
            r.add(en.getKey(), en.getValue());
        }
        JsonArray libList = new JsonArray();
        if (v.has("libraries")) libList.addAll(v.getAsJsonArray("libraries"));
        if (p.has("libraries")) libList.addAll(p.getAsJsonArray("libraries"));
        r.add("libraries", libList);

        if (v.has("arguments")) {
            JsonObject pa = p.has("arguments") ? p.getAsJsonObject("arguments") : new JsonObject();
            JsonObject ca = v.getAsJsonObject("arguments");
            JsonObject na = new JsonObject();
            for (String k : List.of("jvm", "game")) {
                JsonArray a = new JsonArray();
                if (pa.has(k)) a.addAll(pa.getAsJsonArray(k));
                if (ca.has(k)) a.addAll(ca.getAsJsonArray(k));
                na.add(k, a);
            }
            r.add("arguments", na);
        }
        r.addProperty("_base", p.get("_base").getAsString());
        return r;
    }

    static boolean allowed(JsonArray rules) {
        if (rules == null) return true;
        boolean ok = false;
        for (JsonElement e : rules) {
            JsonObject r = e.getAsJsonObject();
            boolean match = !r.has("features");
            if (match && r.has("os")) {
                JsonObject os = r.getAsJsonObject("os");
                if (os.has("name") && !os.get("name").getAsString().equals(OS)) match = false;
                if (os.has("arch") && !os.get("arch").getAsString().equals(arch())) match = false;
            }
            if (match) ok = r.get("action").getAsString().equals("allow");
        }
        return ok;
    }

    static String mavenPath(String name) {
        String[] p = name.split("@")[0].split(":");
        String file = p[1] + "-" + p[2] + (p.length > 3 ? "-" + p[3] : "") + ".jar";
        return p[0].replace('.', '/') + "/" + p[1] + "/" + p[2] + "/" + file;
    }

    static String libKey(String name) {
        String[] p = name.split(":");
        return p[0] + ":" + p[1] + (p.length > 3 ? ":" + p[3] : "");
    }

    // ------------------------------------------------------------------ biblioteki i assety

    List<Path> prepareLibraries(JsonObject v, Path natives) throws Exception {
        Map<String, JsonObject> unique = new LinkedHashMap<>(); // pierwszy wygrywa (loader przed vanillą)
        for (JsonElement e : v.getAsJsonArray("libraries")) {
            JsonObject lib = e.getAsJsonObject();
            unique.putIfAbsent(libKey(lib.get("name").getAsString()) + (lib.has("natives") ? ":n" : ""), lib);
        }
        List<Path> cp = new ArrayList<>();
        int i = 0, n = unique.size();
        for (JsonObject lib : unique.values()) {
            ui.progress(0.05 + 0.25 * (++i) / n);
            if (!allowed(lib.has("rules") ? lib.getAsJsonArray("rules") : null)) continue;
            String name = lib.get("name").getAsString();
            JsonObject dl = lib.has("downloads") ? lib.getAsJsonObject("downloads") : null;

            if (lib.has("natives")) { // stary format natywnych bibliotek (<= 1.18)
                JsonObject nat = lib.getAsJsonObject("natives");
                if (nat.has(OS)) {
                    String key = nat.get(OS).getAsString().replace("${arch}", is64() ? "64" : "32");
                    if (dl != null && dl.has("classifiers") && dl.getAsJsonObject("classifiers").has(key)) {
                        JsonObject cl = dl.getAsJsonObject("classifiers").getAsJsonObject(key);
                        Path jar = libs.resolve(cl.get("path").getAsString());
                        download(cl.get("url").getAsString(), jar, cl.get("sha1").getAsString());
                        extractNatives(jar, natives, lib);
                    }
                }
            }

            JsonObject art = dl != null && dl.has("artifact") ? dl.getAsJsonObject("artifact") : null;
            if (art == null && lib.has("natives")) continue;

            Path jar;
            if (art != null) {
                String path = art.has("path") ? art.get("path").getAsString() : mavenPath(name);
                jar = libs.resolve(path);
                String url = art.has("url") ? art.get("url").getAsString() : "";
                if (!url.isEmpty()) download(url, jar, art.has("sha1") ? art.get("sha1").getAsString() : null);
                else if (!Files.exists(jar)) { ui.log("Brak biblioteki: " + path); continue; }
            } else {
                String path = mavenPath(name);
                jar = libs.resolve(path);
                String base = lib.has("url") ? lib.get("url").getAsString() : LIBRARIES;
                if (!base.endsWith("/")) base += "/";
                download(base + path, jar, null);
            }
            cp.add(jar);
        }
        return cp;
    }

    void extractNatives(Path jar, Path dir, JsonObject lib) throws IOException {
        List<String> exclude = new ArrayList<>();
        if (lib.has("extract") && lib.getAsJsonObject("extract").has("exclude"))
            for (JsonElement e : lib.getAsJsonObject("extract").getAsJsonArray("exclude")) exclude.add(e.getAsString());
        Files.createDirectories(dir);
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry ze = en.nextElement();
                String n = ze.getName();
                if (ze.isDirectory() || exclude.stream().anyMatch(n::startsWith)) continue;
                Path out = dir.resolve(n).normalize();
                if (!out.startsWith(dir)) continue; // ochrona przed zip-slip
                Files.createDirectories(out.getParent());
                try (InputStream in = z.getInputStream(ze)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Zwraca {katalog assetów dla gry, nazwa indeksu}. */
    String[] prepareAssets(JsonObject v) throws Exception {
        JsonObject ai = v.getAsJsonObject("assetIndex");
        String id = ai.get("id").getAsString();
        Path idx = assets.resolve("indexes").resolve(id + ".json");
        download(ai.get("url").getAsString(), idx, ai.get("sha1").getAsString());

        JsonObject index = readJson(idx);
        JsonObject objs = index.getAsJsonObject("objects");
        boolean virtual = index.has("virtual") && index.get("virtual").getAsBoolean();
        Path virtualDir = assets.resolve("virtual").resolve(id);

        ExecutorService pool = Executors.newFixedThreadPool(16);
        AtomicInteger done = new AtomicInteger();
        int total = objs.size();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (Map.Entry<String, JsonElement> en : objs.entrySet()) {
                String name = en.getKey();
                String hash = en.getValue().getAsJsonObject().get("hash").getAsString();
                futures.add(pool.submit(() -> {
                    String p = hash.substring(0, 2) + "/" + hash;
                    Path obj = assets.resolve("objects").resolve(p);
                    download(RESOURCES + p, obj, hash);
                    if (virtual) {
                        Path dest = virtualDir.resolve(name);
                        if (!Files.exists(dest)) {
                            Files.createDirectories(dest.getParent());
                            Files.copy(obj, dest);
                        }
                    }
                    int d = done.incrementAndGet();
                    if (d % 50 == 0 || d == total) ui.progress(0.3 + 0.6 * d / total);
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                try { f.get(); }
                catch (ExecutionException ex) { throw new IOException("Pobieranie assetów: " + ex.getCause().getMessage(), ex.getCause()); }
            }
        } finally {
            pool.shutdownNow();
        }
        return new String[]{(virtual ? virtualDir : assets).toString(), id};
    }

    // ------------------------------------------------------------------ start gry

    public Process launch(Options o) throws Exception {
        Files.createDirectories(root);
        ui.progress(0.01);
        ui.log("Przygotowuję " + o.loader() + " " + o.mc() + "...");

        String javaExe = (o.java() == null || o.java().isBlank()) ? defaultJava() : o.java();

        ensureVanillaJson(o.mc());
        baseJar(o.mc());

        String id = switch (o.loader().toLowerCase()) {
            case "fabric" -> installProfileLoader("https://meta.fabricmc.net/v2/versions/loader/", o.mc());
            case "quilt" -> installProfileLoader("https://meta.quiltmc.org/v3/versions/loader/", o.mc());
            case "forge" -> installForge(o.mc(), javaExe);
            default -> o.mc();
        };
        ui.progress(0.05);

        JsonObject v = resolve(id);
        if ((o.java() == null || o.java().isBlank()) && v.has("javaVersion")) {
            int need = v.getAsJsonObject("javaVersion").get("majorVersion").getAsInt();
            if (need > Runtime.version().feature())
                throw new IOException("Ta wersja wymaga Javy " + need + "+, a launcher działa na Javie "
                        + Runtime.version().feature() + ". Podaj ścieżkę do właściwej Javy w ustawieniach.");
        }

        // jar klienta pod nazwą id (Forge używa ${version_name}.jar w -DignoreList)
        Path jar = versions.resolve(id).resolve(id + ".jar");
        if (!Files.exists(jar)) {
            Files.createDirectories(jar.getParent());
            Files.copy(baseJar(v.get("_base").getAsString()), jar);
        }

        Path natives = root.resolve("natives").resolve(id);
        List<Path> cpList = prepareLibraries(v, natives);
        cpList.add(jar);
        String classpath = String.join(File.pathSeparator, cpList.stream().map(Path::toString).toList());

        ui.log("Pobieram zasoby gry...");
        String[] a = prepareAssets(v);

        Path gameDir = instanceDir(o.mc(), o.loader());
        Files.createDirectories(gameDir.resolve("mods"));
        Files.createDirectories(natives);

        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + o.username()).getBytes(StandardCharsets.UTF_8));
        Map<String, String> vars = new HashMap<>();
        vars.put("auth_player_name", o.username());
        vars.put("version_name", id);
        vars.put("game_directory", gameDir.toString());
        vars.put("assets_root", a[0]);
        vars.put("game_assets", a[0]);
        vars.put("assets_index_name", a[1]);
        vars.put("auth_uuid", uuid.toString().replace("-", ""));
        vars.put("auth_access_token", "0");
        vars.put("auth_session", "0");
        vars.put("clientid", "0");
        vars.put("auth_xuid", "0");
        vars.put("user_type", "legacy");
        vars.put("user_properties", "{}");
        vars.put("version_type", "LightyLauncher");
        vars.put("natives_directory", natives.toString());
        vars.put("launcher_name", "LightyLauncher");
        vars.put("launcher_version", "1.0");
        vars.put("classpath", classpath);
        vars.put("classpath_separator", File.pathSeparator);
        vars.put("library_directory", libs.toString());

        List<String> jvm = new ArrayList<>();
        List<String> game = new ArrayList<>();
        if (v.has("arguments")) {
            JsonObject args = v.getAsJsonObject("arguments");
            collectArgs(args.getAsJsonArray("jvm"), jvm);
            collectArgs(args.getAsJsonArray("game"), game);
        } else { // stary format (<= 1.12)
            jvm.addAll(List.of("-Djava.library.path=${natives_directory}", "-cp", "${classpath}"));
            game.addAll(Arrays.asList(v.get("minecraftArguments").getAsString().split(" ")));
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        cmd.add("-Xmx" + o.ramMb() + "M");
        for (String s : jvm) cmd.add(subst(s, vars));
        cmd.add(v.get("mainClass").getAsString());
        for (String s : game) cmd.add(subst(s, vars));

        ui.progress(1.0);
        ui.log("Uruchamiam grę...");
        return new ProcessBuilder(cmd).directory(gameDir.toFile()).redirectErrorStream(true).start();
    }

    static void collectArgs(JsonArray arr, List<String> out) {
        if (arr == null) return;
        for (JsonElement e : arr) {
            if (e.isJsonPrimitive()) { out.add(e.getAsString()); continue; }
            JsonObject o = e.getAsJsonObject();
            if (!allowed(o.has("rules") ? o.getAsJsonArray("rules") : null)) continue;
            JsonElement val = o.get("value");
            if (val.isJsonArray()) for (JsonElement x : val.getAsJsonArray()) out.add(x.getAsString());
            else out.add(val.getAsString());
        }
    }

    static final Pattern VAR = Pattern.compile("\\$\\{([^}]+)}");

    static String subst(String s, Map<String, String> vars) {
        Matcher m = VAR.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String val = vars.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(val != null ? val : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
