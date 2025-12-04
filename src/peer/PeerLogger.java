package peer;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;

public class PeerLogger {
    private final String peerId;
    private final PrintWriter out;
    private final SimpleDateFormat fmt =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public PeerLogger(String peerId) throws IOException {
        this.peerId = peerId;
        String fileName = "log_peer_" + peerId + ".log";
        this.out = new PrintWriter(new FileWriter(fileName, true), true);
    }

    private String now() {
        return fmt.format(new Date());
    }

    private void log(String msg) {
        synchronized (out) {
            out.println("[" + now() + "]: " + msg);
            out.flush();
        }
    }

    // ----- connection logs -----
    public void logConnectTo(int otherId) {
        log("Peer [" + peerId + "] makes a connection to Peer [" + otherId + "].");
    }

    public void logConnectedFrom(int otherId) {
        log("Peer [" + peerId + "] is connected from Peer [" + otherId + "].");
    }

    // ----- neighbor selection -----
    public void logPreferredNeighbors(Collection<Integer> neighbors) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (int id : neighbors) {
            if (!first) sb.append(", ");
            sb.append(id);
            first = false;
        }
        log("Peer [" + peerId + "] has the preferred neighbors [" + sb + "].");
    }

    public void logOptimisticNeighbor(int otherId) {
        log("Peer [" + peerId + "] has the optimistically unchoked neighbor [" + otherId + "].");
    }

    // ----- choke / unchoke -----
    public void logUnchokedBy(int otherId) {
        log("Peer [" + peerId + "] is unchoked by [" + otherId + "].");
    }

    public void logChokedBy(int otherId) {
        log("Peer [" + peerId + "] is choked by [" + otherId + "].");
    }

    public void logChokingNeighbor(int otherId) {
        log("Peer [" + peerId + "] choking neighbor [" + otherId + "].");
    }

    public void logUnchokingNeighbor(int otherId) {
        log("Peer [" + peerId + "] unchoking neighbor [" + otherId + "].");
    }

    // ----- message reception -----
    public void logReceiveHave(int otherId, int pieceIdx) {
        log("Peer [" + peerId + "] received the 'have' message from [" +
            otherId + "] for the piece [" + pieceIdx + "].");
    }

    public void logReceiveInterested(int otherId) {
        log("Peer [" + peerId + "] received the 'interested' message from [" +
            otherId + "].");
    }

    public void logReceiveNotInterested(int otherId) {
        log("Peer [" + peerId + "] received the 'not interested' message from [" +
            otherId + "].");
    }

    // ----- downloading -----
    public void logDownloadedPiece(int fromId, int pieceIdx, int numPiecesNow) {
        log("Peer [" + peerId + "] has downloaded the piece [" + pieceIdx +
            "] from [" + fromId + "]. Now the number of pieces it has is [" +
            numPiecesNow + "].");
    }

    public void logDownloadComplete() {
        log("Peer [" + peerId + "] has downloaded the complete file.");
    }

    public void logAllPeersComplete() {
        log("Peer [" + peerId + "] has verified all peers have the complete file.");
    }

    // ----- sending messages -----
    public void logSendInterested(int otherId) {
        log("Peer [" + peerId + "] sent the 'interested' message to [" + otherId + "].");
    }

    public void logSendNotInterested(int otherId) {
        log("Peer [" + peerId + "] sent the 'not interested' message to [" + otherId + "].");
    }

    public void logSendRequest(int otherId, int pieceIdx) {
        log("Peer [" + peerId + "] sent the 'request' message to [" + otherId + "] for piece [" + pieceIdx + "].");
    }

    public void logSendPiece(int otherId, int pieceIdx) {
        log("Peer [" + peerId + "] sent the 'piece' message to [" + otherId + "] for piece [" + pieceIdx + "].");
    }

    public void close() {
        out.close();
    }
}
