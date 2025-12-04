package peer.io;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Loads Common.cfg and PeerInfo.cfg from the current working directory.
 * Usage:
 *   Config cfg = Config.load();
 *   Config.Peer me = cfg.getPeerById("1001");
 *   List<Config.Peer> earlier = cfg.getPeersBefore("1001");
 */
public final class Config {
    // Common.cfg fields
    public final int numberOfPreferredNeighbors;
    public final int unchokingInterval;
    public final int optimisticUnchokingInterval;
    public final String fileName;
    public final long fileSize;
    public final int pieceSize;
    public final int numPieces;

    // PeerInfo.cfg (order matters!)
    public final List<Peer> peers;  // immutable

    private Config(
            int k, int p, int m,
            String fileName, long fileSize, int pieceSize,
            List<Peer> peers
    ) {
        this.numberOfPreferredNeighbors = k;
        this.unchokingInterval = p;
        this.optimisticUnchokingInterval = m;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.pieceSize = pieceSize;
        this.numPieces = (int) ((fileSize + pieceSize - 1) / pieceSize);
        this.peers = Collections.unmodifiableList(peers);
    }

    /** Load from current working directory (where you run `java ...`). */
    public static Config load() throws IOException {
        Path wd = Paths.get("").toAbsolutePath();
        return loadFrom(wd);
    }

    /** Load from a specific directory (handy for tests). */
    public static Config loadFrom(Path dir) throws IOException {
        Path commonPath = dir.resolve("Common.cfg");
        Path peerInfoPath = dir.resolve("src/peer/io/PeerInfo1.cfg");

        // ---- Parse Common.cfg
        Integer k = null, p = null, m = null, piece = null;
        String fname = null;
        Long fsize = null;

        try (BufferedReader br = Files.newBufferedReader(commonPath)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = stripComments(line);
                if (line.isBlank()) continue;
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2) continue;
                String key = parts[0];
                String val = parts[1];

                switch (key) {
                    case "NumberOfPreferredNeighbors" -> k = parseInt(key, val);
                    case "UnchokingInterval" -> p = parseInt(key, val);
                    case "OptimisticUnchokingInterval" -> m = parseInt(key, val);
                    case "FileName" -> fname = val;
                    case "FileSize" -> fsize = parseLong(key, val);
                    case "PieceSize" -> piece = parseInt(key, val);
                    default -> { /* ignore unknowns */ }
                }
            }
        }

        require(k != null, "Missing NumberOfPreferredNeighbors in Common.cfg");
        require(p != null, "Missing UnchokingInterval in Common.cfg");
        require(m != null, "Missing OptimisticUnchokingInterval in Common.cfg");
        require(fname != null, "Missing FileName in Common.cfg");
        require(fsize != null, "Missing FileSize in Common.cfg");
        require(piece != null, "Missing PieceSize in Common.cfg");

        // ---- Parse PeerInfo.cfg
        List<Peer> peers = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(peerInfoPath)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = stripComments(line);
                if (line.isBlank()) continue;
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 4) {
                    throw new IOException("Bad PeerInfo.cfg line (need 4 columns): " + line);
                }
                String id = parts[0];
                String host = parts[1];
                int port = parseInt("port", parts[2]);
                boolean hasFile = Objects.equals(parts[3], "1");

                peers.add(new Peer(id, host, port, hasFile));
            }
        }
        if (peers.isEmpty()) throw new IOException("PeerInfo.cfg has no peers");

        return new Config(k, p, m, fname, fsize, piece, peers);
    }

    // ---------- Helpers ----------

    public Peer getPeerById(String id) {
        for (Peer p : peers) if (p.id.equals(id)) return p;
        throw new IllegalArgumentException("Peer id not found: " + id);
    }

    /** Peers that appear BEFORE the given id in PeerInfo.cfg order. */
    public List<Peer> getPeersBefore(String id) {
        List<Peer> res = new ArrayList<>();
        for (Peer p : peers) {
            if (p.id.equals(id)) break;
            res.add(p);
        }
        return res;
    }

    private static String stripComments(String s) {
        int hash = s.indexOf('#');
        int slashes = s.indexOf("//");
        int cut = -1;
        if (hash >= 0) cut = hash;
        if (slashes >= 0) cut = (cut < 0) ? slashes : Math.min(cut, slashes);
        return (cut >= 0) ? s.substring(0, cut) : s;
    }

    private static int parseInt(String key, String v) throws IOException {
        try { return Integer.parseInt(v); }
        catch (NumberFormatException e) { throw new IOException("Bad int for " + key + ": " + v); }
    }

    private static long parseLong(String key, String v) throws IOException {
        try { return Long.parseLong(v); }
        catch (NumberFormatException e) { throw new IOException("Bad long for " + key + ": " + v); }
    }

    private static void require(boolean ok, String msg) throws IOException {
        if (!ok) throw new IOException(msg);
    }

    /** Immutable record for a peer entry. */
    public static final class Peer {
        public final String id;
        public final String host;
        public final int port;
        public final boolean hasFile;

        public Peer(String id, String host, int port, boolean hasFile) {
            this.id = id; this.host = host; this.port = port; this.hasFile = hasFile;
        }

        @Override public String toString() {
            return "Peer{id=%s host=%s port=%d hasFile=%s}".formatted(id, host, port, hasFile);
        }
    }
}
