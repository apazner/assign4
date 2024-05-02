public class TCPend {

    /*
     * TODO
     * sender retransmission - DONE
     * sender listener - DONE
     * handshake start - DONE
     * sender timestamps - DONE
     * reciever timestamps - TODO
     * closing/FIN - TODO
     * recieving buffer/window - TODO
     * print function for snd/rcv - TODO
     */

    public static void main(String[] args) {
        boolean sendingMode = false;

        int port = -1; // port number at which the client will run
        int remotePort = -1; // he port at which the remote receiver is running
        String ip = ""; // the IP address of the remote peer (i.e. receiver).

        String fileName = ""; // file to be sent

        int mtu = -1; // maximum transmission unit in bytes
        int sws = -1; // sliding window size in number of segments

        /*byte[] data = {(byte) 0b01011110, (byte) 0b10000110,
            (byte) 0b01100000, (byte) 0b10101100,
            (byte) 0b00101010, (byte) 0b01110001,
            (byte) 0b10110101, (byte) 0b10000001};
        UDPPacket p = new UDPPacket(0, 0, 0, data);

        //byte[] bytes = p.serialize();
        //p.deserialize(bytes);
        System.out.println(Integer.toBinaryString(p.checkSum())); */

        // check number of args
        if (args.length == 0) {
            argError();
        }

        if (args[2].equals("-s")) {
            sendingMode = true;
            if (args.length != 12) {
                argError();
            }
        } else {
            if (args.length != 8) {
                argError();
            }
        }

        // parse args (after port)
        for (int i = 0; i < args.length; i ++) {
            if ("-p".equals(args[i])) {
                try {
                    port = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    e.printStackTrace();
                }
            } else if ("-s".equals(args[i])) {
                try {
                    ip = args[++i];
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else if ("-a".equals(args[i])) {
                try {
                    remotePort = Integer.parseInt(args[++i]);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else if ("-f".equals(args[i])) {
                try {
                    fileName = args[++i];
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else if ("-m".equals(args[i])) {
                try {
                    mtu = Integer.parseInt(args[++i]);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else if ("-c".equals(args[i])) {
                try {
                    sws = Integer.parseInt(args[++i]);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        if (mtu > 1430) {
            mtuError();
        }

        if (sendingMode) {
			Client.run(port, remotePort, ip, fileName, mtu, sws);
        } else {
			Reciever.run(port, fileName, mtu, sws);
        }
    }

    private static void argError() {
        System.err.println("Error: missing or additional arguments");
        System.exit(1);
    }

    private static void mtuError() {
        System.err.println("Error: mtu must be less than 1430");
        System.exit(1);
    }
}
