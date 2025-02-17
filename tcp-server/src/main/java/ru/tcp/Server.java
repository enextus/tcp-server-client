package ru.tcp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tcp.model.MessageForClient;
import ru.tcp.model.MessageFromClient;

public class Server {
    private static final Logger logger = LoggerFactory.getLogger(Server.class);

    private static final byte[] EMPTY_ARRAY = new byte[0];
    private static final int TIME_OUT_MS = 500;

    private final int port;
    private final InetAddress addr;

    private final Map<SocketAddress, SocketChannel> clients = new HashMap<>();
    private final Queue<SocketAddress> connectedClientsEvents = new ConcurrentLinkedQueue<>();
    private final Queue<SocketAddress> disConnectedClientsEvents = new ConcurrentLinkedQueue<>();
    private final Queue<MessageForClient> messagesForClients = new ArrayBlockingQueue<>(1000);
    private final Queue<MessageFromClient> messagesFromClients = new ArrayBlockingQueue<>(1000);

    public Server(int port) {
        this(null, port);
    }

    public Server(InetAddress addr, int port) {
        logger.debug("addr:{}, port:{}", addr, port);
        this.addr = addr;
        this.port = port;
    }

    public void start() {
        try {
            try (var serverSocketChannel = ServerSocketChannel.open()) {
                serverSocketChannel.configureBlocking(false);
                var serverSocket = serverSocketChannel.socket();
                serverSocket.bind(new InetSocketAddress(addr, port));
                try (var selector = Selector.open()) {
                    serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
                    while (!Thread.currentThread().isInterrupted()) {
                        handleSelector(selector);
                    }
                }
            }
        } catch (Exception ex) {
            throw new TcpServerException(ex);
        }
    }

    public Queue<SocketAddress> getConnectedClientsEvents() {
        return connectedClientsEvents;
    }

    public Queue<SocketAddress> getDisConnectedClientsEvents() {
        return disConnectedClientsEvents;
    }

    public Queue<MessageFromClient> getMessagesFromClients() {
        return messagesFromClients;
    }

    public boolean send(SocketAddress clientAddress, byte[] data) {
        var result = messagesForClients.offer(new MessageForClient(clientAddress, data));
        logger.debug("Scheduled for sending to the client:{}, result:{}", clientAddress, result);
        return result;
    }

    private void handleSelector(Selector selector) {
        try {
            selector.select(this::performIO, TIME_OUT_MS);
            sendMessagesToClients();
        } catch (IOException ex) {
            logger.error("unexpected error:{}", ex.getMessage(), ex);
        } catch (ClientCommunicationException ex) {
            var clintAddress = getSocketAddress(ex.getSocketChannel());
            logger.error("error in client communication:{}", clintAddress, ex);
            disconnect(clintAddress);
        }
    }

    private void performIO(SelectionKey selectedKey) {
        if (selectedKey.isAcceptable()) {
            acceptConnection(selectedKey);
        } else if (selectedKey.isReadable()) {
            readFromClient(selectedKey);
        }
    }

    private void acceptConnection(SelectionKey key) {
        var serverSocketChannel = (ServerSocketChannel) key.channel();
        try {
            var clientSocketChannel = serverSocketChannel.accept();
            var selector = key.selector();
            logger.debug(
                    "accept client connection, key:{}, selector:{}, clientSocketChannel:{}",
                    key,
                    selector,
                    clientSocketChannel);

            clientSocketChannel.configureBlocking(false);
            clientSocketChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);

            var remoteAddress = clientSocketChannel.getRemoteAddress();
            clients.put(remoteAddress, clientSocketChannel);
            connectedClientsEvents.add(remoteAddress);
        } catch (Exception ex) {
            logger.error("can't accept new client on:{}", key);
        }
    }

    private void disconnect(SocketAddress clientAddress) {
        var clientChannel = clients.remove(clientAddress);
        if (clientChannel != null) {
            try {
                clientChannel.close();
            } catch (IOException e) {
                logger.error("clientChannel:{}, closing error:{}", clientAddress, e.getMessage(), e);
            }
        }
        disConnectedClientsEvents.add(clientAddress);
    }

    private void readFromClient(SelectionKey selectionKey) {
        var socketChannel = (SocketChannel) selectionKey.channel();
        logger.debug("{}. read from client", socketChannel);

        var data = readRequest(socketChannel);
        if (data.length == 0) {
            disconnect(getSocketAddress(socketChannel));
        } else {
            messagesFromClients.add(new MessageFromClient(getSocketAddress(socketChannel), data));
        }
    }

    private SocketAddress getSocketAddress(SocketChannel socketChannel) {
        try {
            return socketChannel.getRemoteAddress();
        } catch (Exception ex) {
            throw new ClientCommunicationException("get RemoteAddress error", ex, socketChannel);
        }
    }

    private byte[] readRequest(SocketChannel socketChannel) {
        try {
            List<byte[]> parts = new ArrayList<>();
            var buffer = ByteBuffer.allocate(2);
            int readBytesTotal = 0;
            int readBytes;
            while ((readBytes = socketChannel.read(buffer)) > 0) {
                buffer.flip();
                parts.add(new byte[readBytes]);
                buffer.get(parts.getLast(), 0, readBytes);
                buffer.flip();
                readBytesTotal += readBytes;
            }
            logger.debug("read bytes:{}", readBytesTotal);

            if (readBytesTotal == 0) {
                return EMPTY_ARRAY;
            }
            var result = new byte[readBytesTotal];
            var resultIdx = 0;

            for (var part : parts) {
                System.arraycopy(part, 0, result, resultIdx, part.length);
                resultIdx += part.length;
            }
            return result;
        } catch (Exception ex) {
            throw new ClientCommunicationException("Reading error", ex, socketChannel);
        }
    }

    private void sendMessagesToClients() {
        MessageForClient msg;
        while ((msg = messagesForClients.poll()) != null) {
            var client = clients.get(msg.clientAddress());
            if (client == null) {
                logger.error("client {} not found", msg.clientAddress());
            } else {
                write(client, msg.message());
            }
        }
    }

    private void write(SocketChannel clientChannel, byte[] data) {
        logger.debug("write to client:{}, data.length:{}", clientChannel, data.length);
        var buffer = ByteBuffer.allocate(data.length);
        buffer.put(data);
        buffer.flip();
        try {
            clientChannel.write(buffer);
        } catch (Exception ex) {
            throw new ClientCommunicationException("Write to the client error", ex, clientChannel);
        }
    }
}
