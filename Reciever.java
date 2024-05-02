import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.util.concurrent.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Reciever {

    private static DatagramSocket socket;
    private static InetAddress address;
    private static int remotePort;

    private static int mtu;

    private static boolean running = true;

    //private static byte[] buffer;

    private static int curSeqNum = 0;

    private static AtomicInteger nextExpectedByte = new AtomicInteger();
    private static ConcurrentLinkedQueue<UDPPacket> window;
    private static int capacity;

    // counters
    private static AtomicInteger outOfBoundsPackets = new AtomicInteger();
    private static AtomicInteger corruptedPackets = new AtomicInteger();

    private static Runnable listener = new Runnable() {
        public synchronized void run() {
            try {
                while (running) {
                    byte[] buffer = new byte[mtu];

                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet); // raw data recieved

                    address = packet.getAddress();
                    remotePort = packet.getPort();

                    UDPPacket receivedPacket = new UDPPacket(buffer);

                    // listener might catch generic ack from handshake, do nothing in this case
                    if (receivedPacket.seqNum() < 0) {
                        continue;
                    }

                    if (receivedPacket.finFlag()) {
                        // send ack
                        UDPPacket ack = new UDPPacket(curSeqNum, receivedPacket.seqNum() + 1, receivedPacket.timestamp(), false);
                        sendUDP(ack);


                        UDPPacket fin = new UDPPacket(curSeqNum, -1, receivedPacket.timestamp(), true);
                        sendUDP(fin);

						//System.out.println("sent fin and ack");

                        running = false;
                        break;
                    }

                    // we only add packets to the buffer if we have room in the window, otherwise drop
                    if (window.size() < capacity) {

                        int startingByte = receivedPacket.seqNum() - 1 - receivedPacket.length();

                        // drop packet if it is outside of our window
                        if (startingByte > (mtu - 24) * (capacity - 1) + nextExpectedByte.get()) {
                            // send ack
                            UDPPacket ack = new UDPPacket(curSeqNum, nextExpectedByte.get() + 1, receivedPacket.timestamp(), false);
                            sendUDP(ack);

                            // update counters
                            outOfBoundsPackets.incrementAndGet();

							//System.out.println("outside of window");

                            continue;
                        }

                        // finally, ensure checksum is valid, drop packet if its not
                        if (!checkSumIsValid(receivedPacket)) {
                            // send ack
                            UDPPacket ack = new UDPPacket(-1, nextExpectedByte.get() + 1, receivedPacket.timestamp(), false);
                            sendUDP(ack);

                            //update counters
                            corruptedPackets.incrementAndGet();

							//System.out.println("bad checksum");
							//System.out.println(receivedPacket.toString());

                            continue;
                        }

                        window.add(receivedPacket);
						//System.out.println(receivedPacket.toString());
						

                        // send ack
                        UDPPacket ack = new UDPPacket(curSeqNum, receivedPacket.seqNum() + receivedPacket.length(), receivedPacket.timestamp(), false);
                        sendUDP(ack);
                    } else {
                        // send ack
                        UDPPacket ack = new UDPPacket(curSeqNum, nextExpectedByte.get() + 1, receivedPacket.timestamp(), false);
                        sendUDP(ack);

						//System.out.println("window full");
                    }

                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    };

    public static void run(int port, String fileName, int mtu, int sws) {
        try {
            socket = new DatagramSocket(port);

            window = new ConcurrentLinkedQueue<UDPPacket>();
            capacity = sws;

            Reciever.mtu = mtu;

            // handshake
            byte[] buffer = new byte[mtu];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet); // raw data recieved

			//System.out.println("got handshake packet");
			address = packet.getAddress();
            remotePort = packet.getPort();

            UDPPacket recievedSyn = new UDPPacket(buffer);

            assert(recievedSyn.seqNum() == 0);

            // send SYN+ACK
            UDPPacket synAck = new UDPPacket(curSeqNum, recievedSyn.seqNum() + 1, recievedSyn.timestamp(), false);
            sendUDP(synAck);
            curSeqNum++;
            // end of handshake
			//System.out.println("sent SYN ACK");

            // setup listener
            new Thread(listener).start();

            ArrayList<Byte> fileBytes = new ArrayList<Byte>();

            while (running) {

                for (UDPPacket p : window) {
                    if (p.seqNum() - 1 == nextExpectedByte.get()) { // found next expected packet

                        // parse the packet, add to byte array
                        byte[] data = p.data();
						//System.out.println(p.length());
                        for (byte b : data) {
							//System.out.println(b);
                            fileBytes.add(b);
                        }

                        // remove from window
                        window.remove(p);

                        // update nextExpectedByte
                        nextExpectedByte.addAndGet(data.length);

                        break;
                    }
                }

            }

			// wait for ack from client before closing socket
			buffer = new byte[mtu];
            packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet); // raw data recieved
			

            socket.close();

            // convert bytes to file
            byte[] bytes = new byte[fileBytes.size()];
            for (int i = 0; i < fileBytes.size(); i++) {
                bytes[i] = fileBytes.get(i);
				//System.out.println(bytes[i]);
            }

            convertToFile(bytes, fileName);

            // print stats
            System.out.println("Out of bounds packets: " + outOfBoundsPackets.get());
            System.out.println("Bad checksum packets: " + corruptedPackets.get());

        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private static boolean checkSumIsValid(UDPPacket receivedPacket) {
        int recievedCheckSum = receivedPacket.checkSum();
        receivedPacket.updateChecksum();
		System.out.println(recievedCheckSum + " " + receivedPacket.checkSum());
        return recievedCheckSum == receivedPacket.checkSum();
    }

    private static void sendUDP(UDPPacket udpPacket) {

        try {
            byte[] rawBytes = udpPacket.serialize();
            DatagramPacket packet = new DatagramPacket(rawBytes, rawBytes.length, address, remotePort);
            socket.send(packet);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private static void convertToFile(byte[] bytes, String fileName) {
        try {
            File file = new File(fileName);
            OutputStream os = new FileOutputStream(file);
            os.write(bytes);
            os.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
