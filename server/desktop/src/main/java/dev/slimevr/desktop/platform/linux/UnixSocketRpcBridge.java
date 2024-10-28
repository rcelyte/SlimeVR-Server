package dev.slimevr.desktop.platform.linux;

import dev.slimevr.protocol.ProtocolAPI;
import dev.slimevr.tracking.trackers.Tracker;
import dev.slimevr.util.ann.VRServerThread;
import dev.slimevr.VRServer;
import io.eiren.util.collections.FastList;
import io.eiren.util.logging.LogManager;

import java.io.File;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.List;

public class UnixSocketRpcBridge implements dev.slimevr.bridge.Bridge, dev.slimevr.protocol.ProtocolAPIServer, AutoCloseable {
	public final String socketPath;
	public final ProtocolAPI protocolAPI;
	private ServerSocketChannel socket;
	private final List<UnixSocketConnection> connections = new FastList<UnixSocketConnection>();
	private final ByteBuffer dst = ByteBuffer.allocate(2048), src = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN);

	public UnixSocketRpcBridge(VRServer server, String socketPath, List<Tracker> shareableTrackers) {
		this.socketPath = socketPath;
		this.protocolAPI = server.protocolAPI;
		File socketFile = new File(socketPath);
		if (socketFile.exists()) {
			throw new RuntimeException(socketPath + " socket already exists.");
		}
		socketFile.deleteOnExit();

		server.protocolAPI.registerAPIServer(this);
	}

	@VRServerThread
	private void disconnected() {
		/*synchronized(remoteTrackersByTrackerId) {
			for ((_, value) in remoteTrackersByTrackerId) {
				value.status = TrackerStatus.DISCONNECTED
			}
		}*/
	}

	@Override
	@VRServerThread
	public void dataRead() {
		try {
			for(SocketChannel channel; (channel = socket.accept()) != null;) {
				channel.configureBlocking(false);
				this.connections.add(new UnixSocketConnection(channel));
				LogManager.info("[SolarXR Bridge] Connected to " + channel.getRemoteAddress().toString());
			}
		} catch(IOException e) {
			e.printStackTrace();
		}
		this.connections.removeIf(conn -> {
			for(ByteBuffer message; (message = conn.read()) != null; conn.next())
				this.protocolAPI.onMessage(conn, message);
			return !conn.isConnected();
		});

		// TODO: read SHM here
	}

	@Override
	@VRServerThread
	public void dataWrite() {
		// TODO: write SHM here
	}

	@Override
	@VRServerThread
	public void addSharedTracker(Tracker tracker) {}

	@Override
	@VRServerThread
	public void removeSharedTracker(Tracker tracker) {}

	@Override
	@VRServerThread
	public void startBridge() {
		try {
			this.socket = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
			this.socket.bind(UnixDomainSocketAddress.of(this.socketPath));
			this.socket.configureBlocking(false);
			LogManager.info("[SolarXR Bridge] Socket " + this.socketPath + " created");
		} catch(IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void close() throws Exception {
		if (this.socket != null) {
			this.socket.close();
		}
	}

	@Override
	public boolean isConnected() {return !this.connections.isEmpty();}

	@Override
	public java.util.stream.Stream<dev.slimevr.protocol.GenericConnection> getAPIConnections() {
		return this.connections.stream().map(conn -> conn);
	}
}

