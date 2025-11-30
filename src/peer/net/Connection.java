package peer.net;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

import peer.store.FileManager;
import peer.piece.PieceManager;

public class Connection implements Runnable {

    // ===================== static, per-peer-process state =====================

    // All live connections in this JVM (i.e., this peer process)
    // stats just for debugging scheduler behaviour
    private int sentChokes = 0;
    private int sentUnchokes = 0;
    private int recvChokes = 0;
    private int recvUnchokes = 0;
    private static final List<Connection> ALL =
            new CopyOnWriteArrayList<>();

    // Preferred neighbors + the currently optimistic unchoked neighbor
    private static final Set<Connection> preferred =
            Collections.newSetFromMap(new ConcurrentHashMap<Connection, Boolean>());
    private static volatile Connection optimistic = null;

    // Scheduler configuration (set once from peerProcess)
    private static volatile boolean schedulersStarted = false;
    private static int kPreferred;
    private static long unchokeIntervalMs;
    private static long optimisticIntervalMs;
    private static PieceManager sharedPieces;

    /** Called once from peerProcess after Config.load(). */
    public static synchronized void initSchedulers(
            int numPreferredNeighbors,
            int unchokingIntervalSeconds,
            int optimisticUnchokingIntervalSeconds,
            PieceManager pieces) {

        if (schedulersStarted) return;

        kPreferred = numPreferredNeighbors;
        unchokeIntervalMs = unchokingIntervalSeconds * 1000L;
        optimisticIntervalMs = optimisticUnchokingIntervalSeconds * 1000L;
        sharedPieces = pieces;

        startUnchokeThread();
        startOptimisticThread();
        schedulersStarted = true;
    }

    // Helper: holds rate info for selection
    private static class ConnRate {
        final Connection c;
        final long bytes;
        ConnRate(Connection c, long bytes) { this.c = c; this.bytes = bytes; }
    }

    private static void startUnchokeThread() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(unchokeIntervalMs);
                    recomputePreferredNeighbors();
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }, "UnchokeScheduler");
        t.setDaemon(true);
        t.start();
    }

    private static void startOptimisticThread() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(optimisticIntervalMs);
                    pickOptimisticNeighbor();
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }, "OptimisticUnchokeScheduler");
        t.setDaemon(true);
        t.start();
    }

    // Choose preferred neighbors based on download rate (or randomly if we have full file)
    private static void recomputePreferredNeighbors() {
        if (ALL.isEmpty()) return;

        boolean haveFullFile = (sharedPieces != null && sharedPieces.isComplete());

        List<ConnRate> candidates = new ArrayList<>();
        // drain counters for *all* connections once per interval
        for (Connection c : ALL) {
            long bytes = c.drainBytesFromNeighbor();
            if (c.neighborInterestedInMe) {
                candidates.add(new ConnRate(c, bytes));
            }
        }

        if (candidates.isEmpty()) {
            preferred.clear();
            // only keep the optimistic one (if any) unchoked
            for (Connection c : ALL) {
                boolean shouldBeUnchoked = (c == optimistic);
                c.setChoked(!shouldBeUnchoked);
            }
            return;
        }

        if (haveFullFile) {
            // we have complete file: random k interested neighbors
            Collections.shuffle(candidates);
        } else {
            // sort by download rate (bytes received from them this interval), ties random
            Collections.shuffle(candidates);
            candidates.sort((a, b) -> Long.compare(b.bytes, a.bytes));
        }

        List<Connection> newPreferred = new ArrayList<>();
        int limit = Math.min(kPreferred, candidates.size());
        for (int i = 0; i < limit; i++) {
            newPreferred.add(candidates.get(i).c);
        }

        preferred.clear();
        preferred.addAll(newPreferred);

        // Set choke / unchoke state: preferred + optimistic = unchoked, others choked
        for (Connection c : ALL) {
            boolean shouldBeUnchoked =
                    preferred.contains(c) || (c == optimistic);
            c.setChoked(!shouldBeUnchoked);
        }

        // (If you implement logging, "Change of preferred neighbors" goes here.)
    }

    // Choose an optimistically unchoked neighbor among choked-but-interested ones
    private static void pickOptimisticNeighbor() {
        if (ALL.isEmpty()) return;

        List<Connection> candidates = new ArrayList<>();
        for (Connection c : ALL) {
            if (c.neighborInterestedInMe && c.iChokeNeighbor) {
                candidates.add(c);
            }
        }
        if (candidates.isEmpty()) {
            optimistic = null;
            return;
        }

        Collections.shuffle(candidates);
        Connection chosen = candidates.get(0);

        Connection previous = optimistic;
        optimistic = chosen;

        // Unchoke the new optimistic neighbor
        chosen.setChoked(false);

        // If the previous optimistic neighbor is no longer preferred, choke it
        if (previous != null && previous != chosen && !preferred.contains(previous)) {
            previous.setChoked(true);
        }

        // (If you implement logging, "Change of optimistically unchoked neighbor" goes here.)
    }

    // ===================== instance state & constructor =====================

    private final Socket sock;
    private final String myId;
    private final FileManager files;
    private final PieceManager pieces;

    private DataInputStream din;
    private DataOutputStream dout;

    private byte[] neighborBitfield;           // neighbor's bitfield
    private boolean amChokedByNeighbor = true; // until they unchoke us
    private boolean awaitingPiece = false;     // we sent request, waiting for piece

    // Upload / scheduler side
    private volatile boolean iChokeNeighbor = true;      // whether *we* choke them
    private volatile boolean neighborInterestedInMe = false;
    private volatile long bytesFromNeighborThisInterval = 0L;
    private int remotePeerId = -1;                       // set after handshake

    public Connection(Socket sock, String myId, FileManager files, PieceManager pieces) {
        this.sock = sock;
        this.myId = myId;
        this.files = files;
        this.pieces = pieces;

        ALL.add(this);
    }

    public void start() {
        new Thread(this, "Conn-" + myId + "->" + sock.getRemoteSocketAddress()).start();
    }

    // Used by scheduler: reset byte counter once per interval
    private long drainBytesFromNeighbor() {
        long v = bytesFromNeighborThisInterval;
        bytesFromNeighborThisInterval = 0L;
        return v;
    }

    // Called when we successfully download data from this neighbor
    private void addBytesFromNeighbor(int n) {
        bytesFromNeighborThisInterval += n;
    }

    // Change choke status and send control message if it changed
    private void setChoked(boolean choke) {
    if (iChokeNeighbor == choke) return;  // no change
    iChokeNeighbor = choke;

    if (choke) sentChokes++; else sentUnchokes++;

    System.out.println("[" + myId + "] " +
        (choke ? "CHOKING" : "UNCHOKING") +
        " peer " + remotePeerId);

    try {
        if (choke) sendChoke(); else sendUnchoke();
    } catch (IOException ignored) {}
}


    // ===================== handshake helpers =====================

    private static final byte[] HS_HEADER =
            "P2PFILESHARINGPROJ".getBytes(StandardCharsets.US_ASCII); // 18B
    private static final int HS_TOTAL = 32;

    private static void sendHandshake(OutputStream out, int myPeerId) throws IOException {
        DataOutputStream dout = new DataOutputStream(out);
        byte[] buf = new byte[HS_TOTAL];           // 32 bytes, zeroed
        System.arraycopy(HS_HEADER, 0, buf, 0, HS_HEADER.length);
        buf[28] = (byte)((myPeerId >>> 24) & 0xFF);
        buf[29] = (byte)((myPeerId >>> 16) & 0xFF);
        buf[30] = (byte)((myPeerId >>>  8) & 0xFF);
        buf[31] = (byte)((myPeerId       ) & 0xFF);
        dout.write(buf);
        dout.flush();
    }

    private static int recvHandshake(InputStream in) throws IOException {
        DataInputStream din = new DataInputStream(in);
        byte[] buf = new byte[HS_TOTAL];
        din.readFully(buf);
        for (int i = 0; i < HS_HEADER.length; i++) {
            if (buf[i] != HS_HEADER[i]) throw new IOException("Bad handshake header");
        }
        return ((buf[28] & 0xFF) << 24) |
               ((buf[29] & 0xFF) << 16) |
               ((buf[30] & 0xFF) <<  8) |
                (buf[31] & 0xFF);
    }

    @Override
    public void run() {
        try (var in = sock.getInputStream(); var out = sock.getOutputStream()) {
            sock.setTcpNoDelay(true);
            sock.setSoTimeout(15000);

            din = new DataInputStream(in);
            dout = new DataOutputStream(out);

            int my = Integer.parseInt(myId);
            sendHandshake(out, my);
            int remote = recvHandshake(in);
            this.remotePeerId = remote;

            System.out.println("[" + myId + "] handshake OK with peer " + remote +
                               " via " + sock.getRemoteSocketAddress());

            // Send my bitfield so neighbor can decide interest
            sendBitfield(pieces.myBitfield());

            // Enter message loop
            readLoop();
        } catch (Exception e) {
            System.out.println("[" + myId + "] connection closed: " + e.getMessage());
        } finally {
            System.out.printf("[%s] stats with peer %d: sentChoke=%d sentUnchoke=%d recvChoke=%d recvUnchoke=%d%n",
            myId, remotePeerId, sentChokes, sentUnchokes, recvChokes, recvUnchokes);
    
            ALL.remove(this);
        }
    }

    // ===================== message framing =====================

    private static final byte MSG_CHOKE           = 0;
    private static final byte MSG_UNCHOKE         = 1;
    private static final byte MSG_INTERESTED      = 2;
    private static final byte MSG_NOT_INTERESTED  = 3;
    private static final byte MSG_HAVE            = 4;
    private static final byte MSG_BITFIELD        = 5;
    private static final byte MSG_REQUEST         = 6;
    private static final byte MSG_PIECE           = 7;

    private static String typeName(int t) {
        return switch (t) {
            case 0 -> "choke";
            case 1 -> "unchoke";
            case 2 -> "interested";
            case 3 -> "not_interested";
            case 4 -> "have";
            case 5 -> "bitfield";
            case 6 -> "request";
            case 7 -> "piece";
            default -> "unknown(" + t + ")";
        };
    }

    private void sendFrame(byte type, byte[] payload) throws IOException {
        int payloadLen = (payload == null) ? 0 : payload.length;
        int len = 1 + payloadLen;
        dout.writeInt(len);
        dout.writeByte(type);
        if (payloadLen > 0) dout.write(payload);
        System.out.printf("[%s] → %s (%d bytes payload)%n",
                myId, typeName(type), (payload == null ? 0 : payload.length));
        dout.flush();
    }

    public void sendChoke()         throws IOException { sendFrame(MSG_CHOKE, null); }
    public void sendUnchoke()       throws IOException { sendFrame(MSG_UNCHOKE, null); }
    public void sendInterested()    throws IOException { sendFrame(MSG_INTERESTED, null); }
    public void sendNotInterested() throws IOException { sendFrame(MSG_NOT_INTERESTED, null); }

    /** have(index) — payload is a 4-byte piece index (big-endian) */
    public void sendHave(int pieceIndex) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(4);
        bb.putInt(pieceIndex);
        sendFrame(MSG_HAVE, bb.array());
    }

    /** bitfield(raw bytes) — payload is your bitfield as a packed byte array */
    public void sendBitfield(byte[] bitfield) throws IOException {
        sendFrame(MSG_BITFIELD, bitfield);
    }

    /** request(index) — payload is a 4-byte piece index (big-endian) */
    public void sendRequest(int pieceIndex) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(4);
        bb.putInt(pieceIndex);
        sendFrame(MSG_REQUEST, bb.array());
    }

    /** piece(index + data) — payload is 4-byte index followed by raw piece bytes */
    public void sendPiece(int pieceIndex, byte[] data) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(4 + data.length);
        bb.putInt(pieceIndex);
        bb.put(data);
        sendFrame(MSG_PIECE, bb.array());
    }

    // Decide interest based on neighbor's bitfield
    private void maybeSendInterest() throws IOException {
        if (neighborBitfield == null) return;
        int next = pieces.nextNeededFrom(neighborBitfield);
        if (next >= 0) sendInterested(); else sendNotInterested();
    }

    private void maybeRequestNext() throws IOException {
        if (amChokedByNeighbor || awaitingPiece || neighborBitfield == null) return;
        int idx = pieces.nextNeededFrom(neighborBitfield);
        if (idx >= 0) {
            sendRequest(idx);
            awaitingPiece = true;
        } else {
            sendNotInterested();
        }
    }

    private static void setBit(byte[] bf, int idx) {
        if (bf == null) return;
        int byteIx = idx >>> 3, bit = 7 - (idx & 7);
        if (byteIx < bf.length) bf[byteIx] = (byte)(bf[byteIx] | (1 << bit));
    }

    private void readLoop() throws IOException {
        while (true) {
            int len  = din.readInt();
            int type = din.readUnsignedByte();
            int payloadLen = len - 1;
            byte[] payload = (payloadLen > 0) ? new byte[payloadLen] : null;
            System.out.printf("[%s] ← %s (%d bytes payload)%n",
                    myId, typeName(type), payloadLen);
            if (payloadLen > 0) din.readFully(payload);

            switch (type) {
                case MSG_CHOKE          -> onChoke();
                case MSG_UNCHOKE        -> onUnchoke();
                case MSG_INTERESTED     -> onInterested();
                case MSG_NOT_INTERESTED -> onNotInterested();
                case MSG_HAVE -> {
                    int idx = ByteBuffer.wrap(payload).getInt();
                    onHave(idx);
                }
                case MSG_BITFIELD -> onBitfield(payload);
                case MSG_REQUEST -> {
                    int idx = ByteBuffer.wrap(payload).getInt();
                    onRequest(idx);
                }
                case MSG_PIECE -> {
                    ByteBuffer bb = ByteBuffer.wrap(payload);
                    int idx = bb.getInt();
                    byte[] data = new byte[bb.remaining()];
                    bb.get(data);
                    onPiece(idx, data);
                }
                default -> throw new IOException("Unknown msg type: " + type);
            }
        }
    }

    // ===================== message handlers =====================

    private void onChoke() {
        recvChokes++;
        amChokedByNeighbor = true;
        awaitingPiece = false;  // any in-flight request won’t arrive
    }

    private void onUnchoke() {
        recvUnchokes++;
        amChokedByNeighbor = false;
        try {
            maybeSendInterest();
            maybeRequestNext();
        } catch (IOException ignored) {}
    }

    private void onInterested() {
    neighborInterestedInMe = true;
    // Immediately unchoke this peer so they can start downloading.
    // The scheduler can still re-choke / re-unchoke later.
    setChoked(false);
}

    private void onNotInterested() {
        neighborInterestedInMe = false;
    }

    private void onHave(int pieceIndex) {
        if (neighborBitfield != null) setBit(neighborBitfield, pieceIndex);
        if (!pieces.have(pieceIndex)) {
            try {
                sendInterested();         // harmless if already interested
                maybeRequestNext();
            } catch (IOException ignored) {}
        }
    }

    private void onBitfield(byte[] bitfield) {
        neighborBitfield = bitfield;
        try {
            maybeSendInterest();          // decide interested/not interested
            if (!amChokedByNeighbor) maybeRequestNext();
        } catch (IOException ignored) {}
    }

    private void onRequest(int pieceIndex) {
        // We only upload to neighbors we are NOT choking
        if (iChokeNeighbor) return;
        if (!pieces.have(pieceIndex)) return;
        try {
            byte[] data = files.readPiece(pieceIndex);
            sendPiece(pieceIndex, data);
        } catch (IOException ignored) {}
    }

    private void onPiece(int pieceIndex, byte[] data) {
        try {
            files.writePiece(pieceIndex, data);
            pieces.markHave(pieceIndex);
            addBytesFromNeighbor(data.length);
            System.out.println("[" + myId + "] stored piece " + pieceIndex +
                               " (" + data.length + " bytes)");

            sendHave(pieceIndex);         // tell this neighbor we now have it

            awaitingPiece = false;

            if (pieces.isComplete()) {
                System.out.println("[" + myId + "] download complete");
                sendNotInterested();
            } else if (!amChokedByNeighbor) {
                maybeRequestNext();
            }
        } catch (IOException ignored) {}
    }
}
