package peer;

import peer.io.Config;
import peer.net.Server;
import peer.net.Client;
import peer.net.Connection;
import peer.store.FileManager;
import peer.piece.PieceManager;

import java.util.List;

public class peerProcess {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: java peer.peerProcess <peerId>");
            System.exit(1);
        }

        String peerId = args[0];
        System.out.println("Peer " + peerId + " starting...");
        System.out.println("Working dir is where Common.cfg / PeerInfo.cfg live.");

        // -------- load config & my row ----------
        Config cfg = Config.load();
        Config.Peer me = cfg.getPeerById(peerId);
        if (me == null) {
            System.err.println("No peer with id " + peerId + " found in PeerInfo.cfg");
            System.exit(1);
        }

        // all peers that appear before me in PeerInfo.cfg (for outgoing connects)
        List<Config.Peer> earlier = cfg.getPeersBefore(peerId);

        // -------- file + piece managers ----------
        FileManager files   = new FileManager(peerId, cfg);
        PieceManager pieces = new PieceManager(cfg.numPieces, me.hasFile);

        // -------- logger ----------
        PeerLogger logger = new PeerLogger(peerId);
        Connection.setLogger(logger);   // make it visible to all Connection instances

        // -------- global schedulers (choke/unchoke) ----------
        Connection.initSchedulers(
                cfg.numberOfPreferredNeighbors,
                cfg.unchokingInterval,
                cfg.optimisticUnchokingInterval,
                pieces
        );

        // -------- server: accepts incoming peers ----------
        Server server = new Server(me.port, socket -> {
            System.out.println("ACCEPT from " + socket.getRemoteSocketAddress());
            // true = incoming side of the TCP connection
            new Connection(socket, peerId, files, pieces, true).start();
        });
        Thread serverThread = new Thread(server, "Server-" + peerId);
        serverThread.start();

        // -------- client: connect to earlier peers ----------
        Client client = new Client();
        for (Config.Peer p : earlier) {
            try {
                var s = client.connect(p.host, p.port);
                System.out.println("CONNECT to " + p.id + " @" + p.host + ":" + p.port);
                // false = outgoing side of the TCP connection
                new Connection(s, peerId, files, pieces, false).start();
            } catch (Exception e) {
                System.err.println("Failed to connect to peer " + p.id + ": " + e);
                e.printStackTrace();
            }
        }

        // -------- completion watcher (global termination) ----------
        Thread watcher = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(300000);

                    if (pieces.isComplete()
                            && Connection.allPeersComplete()) {
                        System.out.println("Peer " + peerId + " sees global completion; exiting.");
                        System.exit(0); // triggers shutdown hook
                    }
                }
            } catch (InterruptedException ignored) {
            }
        }, "CompletionWatcher-" + peerId);
        watcher.setDaemon(true);
        watcher.start();

        // -------- shutdown hook ----------
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Peer " + peerId + " shutting down.");
            server.stop();
            logger.close();
        }));

        // keep main thread alive
        Thread.currentThread().join();
    }
}
