package dev.slimevr.desktop.platform.linux;

import dev.slimevr.protocol.ConnectionContext;
import dev.slimevr.protocol.GenericConnection;
import io.eiren.util.logging.LogManager;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.util.UUID;

public class UnixSocketConnection implements GenericConnection {
	public final UUID id;
	public final ConnectionContext context;
	private final ByteBuffer dst = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN);
	private final ByteBuffer src = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN);
	private SocketChannel channel;

	public UnixSocketConnection(SocketChannel channel) {
		this.id = UUID.randomUUID();
		this.context = new ConnectionContext();
		this.channel = channel;
	}

	@Override
	public UUID getConnectionId() {
		return id;
	}

	@Override
	public ConnectionContext getContext() {
		return this.context;
	}

	public boolean isConnected() {
		return this.channel != null;
	}

	private void resetChannel() {
		try {
			this.channel.close();
		} catch(IOException e) {
			e.printStackTrace();
		}
		this.channel = null;
	}

	private boolean checkConnected() {
		if (this.channel == null)
			return false;
		if (this.channel.isConnected())
			return true;
		LogManager.info("[SolarXR Bridge] Client disconnected");
		this.resetChannel();
		return false;
	}

	@Override
	public void send(ByteBuffer bytes) {
		if (!this.checkConnected())
			return;
		try {
			this.src.putInt(bytes.remaining() + 4);
			this.src.put(bytes);
			this.src.flip();
			while (this.src.hasRemaining()) {
				this.channel.write(this.src);
			}
			this.src.clear();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public ByteBuffer read() {
		if(dst.position() < 4) {
			if (!this.checkConnected())
				return null;
			try {
				int result = this.channel.read(dst);
				if (result == -1) {
					LogManager.info("[SolarXR Bridge] Reached end-of-stream on connection");
					this.resetChannel();
					return null;
				}
				if (result == 0 || dst.position() < 4) {
					return null;
				}
			} catch(IOException e) {
				e.printStackTrace();
				this.resetChannel();
				return null;
			}
		}
		int messageLength = dst.getInt(0);
		if (messageLength > 1024) {
			LogManager.severe("[SolarXR Bridge] Buffer overflow on socket. Message length: " + messageLength);
			this.resetChannel();
			return null;
		}
		if (dst.position() < messageLength) {
			return null;
		}
		ByteBuffer message = dst.slice();
		message.position(4);
		message.limit(messageLength);
		return message;
	}

	public void next() {
		int messageLength = dst.getInt(0);
		int originalpos = dst.position();
		dst.position(messageLength);
		dst.compact();
		dst.position(originalpos - messageLength);
	}
}
