public class UDPPacket {

    private int seqNum; // 4 bytes
    private int ack; // 4 bytes
    private long timestamp; // 8 bytes

    // length + flags = 4 bytes
    private int length; // 29 bits
    private boolean synFlag;
    private boolean ackFlag;
    private boolean finFlag;

    private int checkSum; // 2 bytes, 2 bytes worth of leading 0s

    private byte[] data;
    //private int mtu;

    public UDPPacket(int seqNum, int ack, long timestamp, boolean fin) {
        this.seqNum = seqNum;
        this.ack = ack;
        this.timestamp = timestamp;

        synFlag = seqNum == 0;
        ackFlag = ack >= 0;

        finFlag = fin;
    }

    public UDPPacket(int seqNum, int ack, long timestamp, byte[] data) {
        this.seqNum = seqNum;
        this.ack = ack;
        this.timestamp = timestamp;

        this.length = data.length;

        this.data = new byte[this.length];
        for (int i = 0; i < data.length; i++) {
            this.data[i] = data[i];
        }

        updateChecksum();
        assert(this.checkSum <= 0xFFFF);

        synFlag = seqNum == 0;
        ackFlag = ack >= 0;
    }

    public UDPPacket(byte[] rawData) {
        this.deserialize(rawData);
    }

    public byte[] serialize() {
        // header is 24 bytes long
        // 4 + 4 + 8 + 4 + 4 (checksum = 2 + 2 bytes of 0s)
        byte[] bytes = new byte[24 + length];

        int index = 0;

        // seqNum
        for (byte b : intToBytes(seqNum)) {
            bytes[index++] = b;
        }

        assert(index == 4);

        // ack
        for (byte b : intToBytes(ack)) {
            bytes[index++] = b;
        }

        assert(index == 8);

        // timestamp
        for (byte b : longToBytes(timestamp)) {
            bytes[index++] = b;
        }

        assert(index == 16);

        // length and flags compressed
        for (byte b : intToBytes(lenAndFlags())) {
            bytes[index++] = b;
        }

        // checksum
        assert(checkSum <= 0xFFFF);
        for (byte b : intToBytes(checkSum)) {
            bytes[index++] = b;
        }

        // data

		if (data == null) {
			return bytes;
		}

        for (byte b : data) {
            bytes[index++] = b;
        }

        return bytes;
    }

    public void deserialize(byte[] bytes) {
        // zero our data
        seqNum = 0;
        ack = 0;
        timestamp = 0;
        length = 0;
        checkSum = 0;

        // byte array index
        int index = 0;

        // seq
        for (int shift = 24; index < 4; shift -= 8) {
            seqNum |= (unsignedByte(bytes[index++]) << shift);
        }

        // ack
        for (int shift = 24; index < 8; shift -= 8) {
            ack |= (unsignedByte(bytes[index++]) << shift);
        }

        // timestamp
        for (int shift = 56; index < 16; shift -= 8) {
            //System.out.println(unsignedByte(bytes[index]));
            timestamp |= (unsignedByte(bytes[index++]) << shift);
        }

        // length and flags compressed
        int compressed = 0;
        for (int shift = 24; index < 20; shift -= 8) {
			//System.out.println(unsignedByte(bytes[index]));
            compressed |= (unsignedByte(bytes[index++]) << shift);
        }

		//System.out.println(Integer.toBinaryString(compressed));
        parseLenAndFlags(compressed);

        // checksum
        for (int shift = 24; index < 24; shift -= 8) {
            checkSum |= (unsignedByte(bytes[index++]) << shift);
        }

        // data

		//System.out.println(this.length);

		if (this.length == 0) return;

        data = new byte[this.length];
        assert(bytes.length - index == this.length);

		int tempSize = index + this.length;
        for (int i = 0; index < tempSize; i++) {
			
            data[i] = bytes[index++];
			//System.out.println(data[i]);
        }
    }

    // recalculate checksum using current state
    public int updateChecksum() {
        checkSum = 0;

        byte[] rawData = this.serialize();

        long[] segments = new long[(rawData.length + 1) / 2];

        // split data into 16bit segments
        for (int i = 0; i < rawData.length - 1; i += 2) {
            long bits = (unsignedByte(rawData[i + 1]) << 8) | unsignedByte(rawData[i]);
            segments[i / 2] = bits;
        }

        // add up segments
        long cur = segments[0];

        for (int i = 1; i < segments.length; i++) {
            cur += segments[i];

            if ((cur >> 16) != 0) {
                cur &= 0xFFFF; // remove the overflow
                cur++;
            }
        }

        checkSum = (int) ((~cur) & 0xFFFF);

        return checkSum;
    }

    private byte[] intToBytes(int x) {
        byte[] bytes = new byte[4];
        int index = 0;
        for (int shift = 24; shift >= 0; shift -= 8) {
            bytes[index++] = (byte) (x >> shift);
        }

        return bytes;
    }

    private byte[] longToBytes(long x) {
        byte[] bytes = new byte[8];
        int index = 0;
        for (int shift = 56; shift >= 0; shift -= 8) {
            bytes[index++] = (byte) (x >> shift);
        }

        return bytes;
    }

    private long unsignedByte(byte b) {
        return 0xFF & b;
    }

    private int lenAndFlags() {
        int compressed = 0;
        compressed |= length << 3;

        if (synFlag) {
            compressed |= (1 << 2);
        }

        if (ackFlag) {
            compressed |= (1 << 1);
        }

        if (finFlag) {
            compressed |= 1;
        }

        return compressed;
    }

    private void parseLenAndFlags(int compressed) {
        length = compressed >> 3;

        synFlag = ((compressed >> 2) & 1) == 1;
        ackFlag = ((compressed >> 1) & 1) == 1;
        finFlag = ((compressed) & 1) == 1;
    }

    // accessors
    public int seqNum() {
        return seqNum;
    }

    public int ack() {
        return ack;
    }

    public long timestamp() {
        return timestamp;
    }

	public void setTimestamp(long x) {
		timestamp = x;	
		updateChecksum();
	}

    public int length() {
        return length;
    }

    public boolean synFlag() {
        return synFlag;
    }

    public boolean ackFlag() {
        return ackFlag;
    }

    public boolean finFlag() {
        return finFlag;
    }

    public int checkSum() {
        return checkSum;
    }

    public byte[] data() {
        return data;
    }

    @Override
    public String toString() {
        return "Seq: " + seqNum + " Ack: " + ack + " Timestamp: " + timestamp + " Length: " + length + " Checksum: " + checkSum;
    }
}
