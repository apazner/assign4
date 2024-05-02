import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

public class Client {
    private static DatagramSocket socket;
    //private static byte[] buffer;
    private static AtomicBoolean running = new AtomicBoolean(true);

    private static int mtu;

    private static InetAddress address;
    private static int remotePort;

    private static int curSeqNum = 0;

    private static UDPPacket[] udpPackets;
    private static double[] timers;

    private static AtomicInteger lastAckSeqNum = new AtomicInteger();
    private static AtomicInteger ackRepeatCounter = new AtomicInteger();

    private static AtomicInteger windowStart = new AtomicInteger();
    private static AtomicInteger windowEnd = new AtomicInteger(); // inclusive

    private static AtomicLong timeout = new AtomicLong(5000); // miliseconds
    private static double ertt;
    private static double edev;

    // counters
    private static AtomicInteger bytesSent = new AtomicInteger();
    private static AtomicInteger packetsSent = new AtomicInteger();
    private static AtomicInteger retransmissions = new AtomicInteger();
    private static AtomicInteger duplicateAcks = new AtomicInteger();

    private static Runnable listener = new Runnable() {
        public synchronized void run() {
            try {
                while (running.get()) {
                    byte[] buffer = new byte[mtu];
    
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet); // raw data recieved

                    // update counters
                    packetsSent.incrementAndGet();

                    UDPPacket receivedPacket = new UDPPacket(buffer);
                    print(receivedPacket, false);

                    assert(receivedPacket.ackFlag() == true);

                    // handle repeat acks
                    if (lastAckSeqNum.get() == receivedPacket.ack()) {
                        ackRepeatCounter.incrementAndGet();

                        // update counters
                        duplicateAcks.incrementAndGet();
                    } else {
                        ackRepeatCounter.set(1);
                    }

                    lastAckSeqNum.set(receivedPacket.ack());

                    // remove from window

					if (windowStart.get() == udpPackets.length) {
						continue;
					}

                    if(udpPackets[windowStart.get()].seqNum() + udpPackets[windowStart.get()].length() == receivedPacket.ack()) {
                        windowStart.getAndIncrement();
                    }

                    Client.calculateTimeout(receivedPacket.seqNum(), receivedPacket.timestamp());

                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    };

    private static Runnable retransmitter = new Runnable() {
        public synchronized void run() {
            while (running.get()) {
                for (int i = windowStart.get(); i < windowEnd.get(); i++) { // loop over current window
					if (!running.get()) break;

                    double curTime = System.nanoTime() / 1000000;
					//System.out.println(curTime + " " + timers[i] + " " + timeout.get());
                    if (curTime - timers[i] > timeout.get()) { // retransmit
						udpPackets[i].setTimestamp(System.nanoTime());
                        sendUDP(udpPackets[i]);
                        timers[i] = System.nanoTime() / 1000000; // set timeout timer start time (in ms)

						//System.out.println("retransmit due to timeout");
                        // update counters
                        retransmissions.incrementAndGet();
                        packetsSent.incrementAndGet();
                        bytesSent.addAndGet(udpPackets[i].length());
                    }
    
                    // retransmit if acked 3 times
                    if (ackRepeatCounter.get() == 3 && udpPackets[i].seqNum() == lastAckSeqNum.get()) {
						udpPackets[i].setTimestamp(System.nanoTime());
                        sendUDP(udpPackets[i]);
                        timers[i] = System.nanoTime() / 1000000; // set timeout timer start time (in ms)
                        ackRepeatCounter.set(1);

						//System.out.println("retransmit due to 3x");

                        // update counters
                        retransmissions.incrementAndGet();
                        packetsSent.incrementAndGet();
                        bytesSent.addAndGet(udpPackets[i].length());
                    }
                }
            }
        }
    };


    // https://www.baeldung.com/udp-in-java

    // The receiver drops packets if the sliding window buffer is filled up and the sender stop sending packets if its window is filled with un-ACKed segments.

    public static void run(int port, int rPort, String remoteIP, String fileName, int mtu, int sws) {

        try {
            remotePort = rPort;
            address = InetAddress.getByName(remoteIP);
            Client.mtu = mtu;

            socket = new DatagramSocket(port);
			socket.connect(address, rPort);

            // inital part of handshake
            UDPPacket udpPacket = new UDPPacket(curSeqNum++, -1, System.nanoTime(), false);
            sendUDP(udpPacket);

            // update counters
            packetsSent.incrementAndGet();

            // wait for SYN + ACK
            byte[] buffer = new byte[mtu];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet); // raw data recieved

            // update counters
            packetsSent.incrementAndGet();

            UDPPacket recievedUDP = new UDPPacket(buffer);
            print(recievedUDP, false);

            if (recievedUDP.synFlag() && recievedUDP.ackFlag()) {
                // handle repeat acks
                lastAckSeqNum.set(recievedUDP.ack());
                ackRepeatCounter.set(1);

                UDPPacket ackPacket = new UDPPacket(-1, recievedUDP.seqNum() + 1, System.nanoTime(), false); // send ack
                sendUDP(ackPacket);

                // update counters
                packetsSent.incrementAndGet();

                Client.calculateTimeout(recievedUDP.seqNum(), recievedUDP.timestamp());
            }
            // end of handshake

            File file = new File(fileName);
            byte[] fileBytes = readFileToByteArray(file);

            int segmentNumber = fileBytes.length / mtu;

            if (fileBytes.length % mtu != 0) {
                segmentNumber++;
            }

            udpPackets = new UDPPacket[segmentNumber];

            int maxDataPerPacket = mtu - 24; // header is 24 bytes

			//System.out.println(segmentNumber);

            // precompute/parse the file into UDP packets
            for (int s = 0, f = 0; s < segmentNumber; s++) { // loop over file bytes
                int size = (s != segmentNumber - 1) ? maxDataPerPacket : fileBytes.length - f;

                byte[] data = new byte[size];

				int tempFileIndex = f + size;
                for (int i = 0; f < tempFileIndex; i++) {
                    data[i] = fileBytes[f++];

					//System.out.println(data[i]);
                }

				//System.out.println(data.length);

                //UDPPacket packet = new UDPPacket(curSeqNum, 1, System.nanoTime(), data); // f - size
                udpPackets[s] = new UDPPacket(curSeqNum, 1, System.nanoTime(), data);//packet;

				// update seqnum
                curSeqNum += size;
            }

			timers = new double[udpPackets.length];

            // setup listener
            new Thread(listener).start();

            // setup retransmitter
            new Thread(retransmitter).start();


            // sliding window/flow control
            while (windowStart.get() < udpPackets.length) {
                if ((windowEnd.get() + 1) - windowStart.get() < sws) { // room to send
                    if (windowEnd.get() < udpPackets.length) { // more packets to add to window
       
						udpPackets[windowEnd.get()].setTimestamp(System.nanoTime());
                        sendUDP(udpPackets[windowEnd.get()]);
						//System.out.println("sent packet values: " + udpPackets[windowEnd.get()].toString());
                        timers[windowEnd.get()] = System.nanoTime() / 1000000; // set timeout timer start time (in ms)

                        // update counters
                        packetsSent.incrementAndGet();
                        bytesSent.addAndGet(udpPackets[windowEnd.get()].length());

						windowEnd.getAndIncrement();
                    }
                }
            }

            running.set(false);

            UDPPacket fin = new UDPPacket(curSeqNum, -1, System.nanoTime(), true);
            sendUDP(fin);

            // update counters
            packetsSent.incrementAndGet();

            // recieve ack
            buffer = new byte[mtu];
            packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet); // raw data recieved

			UDPPacket temp = new UDPPacket(buffer);
			print(temp, false);

			//System.out.println("got ack");

			if (!temp.finFlag()) { // listener might have already grabbed fin
				// recieve fin
		        buffer = new byte[mtu];
		        packet = new DatagramPacket(buffer, buffer.length);
		        socket.receive(packet); // raw data recieved

				//System.out.println("got fin");
			}

            UDPPacket receivedPacket = new UDPPacket(buffer);
            // send final ack
            UDPPacket ack = new UDPPacket(-1, receivedPacket.seqNum() + 1, System.nanoTime(), false);
            sendUDP(ack);

            // update counters
            packetsSent.incrementAndGet();

            socket.close();

            // print stats
            System.out.println("Data sent (bytes): " + bytesSent.get());
            System.out.println("Packets sent: " + packetsSent.get());
            System.out.println("Retransmissions: " + retransmissions.get());
            System.out.println("Duplicate acks: " + duplicateAcks.get());

        } catch (Exception e) {
            e.printStackTrace();
        }
        
    }

    private static void sendUDP(UDPPacket udpPacket) {
        try {
            byte[] rawBytes = udpPacket.serialize();
            DatagramPacket packet = new DatagramPacket(rawBytes, rawBytes.length, address, remotePort);
            socket.send(packet);

            print(udpPacket, true);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private static synchronized void calculateTimeout(int seqNum, long timestamp) {
		timestamp /= 1000000;
        if (seqNum == 0) {
            ertt = (System.nanoTime() / 1000000) - timestamp;
            edev = 0;
            timeout.set((long) (2 * ertt));
        } else {
            double a = 0.875, b = 0.75;
            double srtt = (System.nanoTime() / 1000000) - timestamp;
            double sdev = Math.abs(srtt - ertt);
            ertt = a * ertt + (1 - a) * srtt;
            edev = b * edev + (1 - b) * sdev;
            timeout.set((long) (ertt + 4 * edev));
        }
    }

    private static void print(UDPPacket packet, boolean sending) {

        String sendRcv = sending ? "snd" : "rcv";

		//if (!sending) return;

        String flags = "";
        if (packet.synFlag()) {
            flags += "S ";
        } else {
            flags += "- ";
        }

        if (packet.ackFlag()) {
            flags += "A ";
        } else {
            flags += "- ";
        }

        if (packet.finFlag()) {
            flags += "F ";
        } else {
            flags += "- ";
        }

        if (packet.length() != 0) {
            flags += "D ";
        } else {
            flags += "- ";
        }

		int ack = packet.ack() == -1 ? 0 : packet.ack();
		int seq = packet.seqNum() == -1 ? 1 : packet.seqNum();

        System.out.println(sendRcv + " " + (packet.timestamp() / 1000000) + " " + flags + seq + " " + packet.length() + " " + ack);
    }

    // https://gist.github.com/absalomhr/ce11c2e43df517b2571b1dfc9bc9b487
    private static byte[] readFileToByteArray(File file) {
        FileInputStream fis = null;
        byte[] bytes = new byte[(int) file.length()];
        
        try {
            fis = new FileInputStream(file);
            fis.read(bytes);
            fis.close();

        } catch (IOException e) {
            e.printStackTrace();
        }

        return bytes;
    }

}
