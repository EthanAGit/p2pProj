package peer;

import peer.io.Config;
import peer.net.Server;
import peer.net.Client;
import peer.net.Connection;
import java.util.List;
import peer.store.FileManager;
import peer.piece.PieceManager;

public class peerProcess {
    public static void main(String[] args) throws Exception {   // <-- add this
        if (args.length != 1) {
            System.err.println("Usage: java peer.peerProcess <peerId>");
            System.exit(1);
        }
        String peerId = args[0];
        System.out.println("Peer " + peerId + " starting...");
        System.out.println("Working dir is where Common.cfg / PeerInfo.cfg live.");

        Config cfg = Config.load();
        Config.Peer me = cfg.getPeerById(peerId);
        List<Config.Peer> earlier = cfg.getPeersBefore(peerId);
        FileManager files = new FileManager(peerId, cfg);
        PieceManager pieces = new PieceManager(cfg.numPieces, me.hasFile);
          peer.net.Connection.initSchedulers(
                cfg.numberOfPreferredNeighbors,
                cfg.unchokingInterval,
                cfg.optimisticUnchokingInterval,
                pieces);
        // 1) Start the server in its own thread (so it accepts while we also connect out)
        Server server = new Server(me.port, socket -> {
            System.out.println("ACCEPT from " + socket.getRemoteSocketAddress());
            new Connection(socket, peerId, files, pieces).start();
        });
        Thread serverThread = new Thread(server, "Server-" + peerId);
        serverThread.start();

        // 2) Connect to earlier peers (each connect returns a Socket)
        Client client = new Client();
        for (Config.Peer p : earlier) {
            try {
                var s = client.connect(p.host, p.port);
                System.out.println("CONNECT to " + p.id + " @" + p.host + ":" + p.port);
                new Connection(s, peerId, files, pieces).start();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // 3) Keep the process alive; stop server on shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Peer " + peerId + " shutting down.");
            server.stop();
        }));
        Thread.currentThread().join();
    }
        
    }