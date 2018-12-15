import java.net.*;
import java.io.*;
import java.util.*;

class StudentSocketImpl extends BaseSocketImpl {

  // SocketImpl data members:
  //   protected InetAddress address;
  //   protected int port;
  //   protected int localport;

    private int state;
    private int seqNum;
    private int ackNum;
    private Hashtable<Integer, TCPTimerTask> timerList = new Hashtable<Integer, TCPTimerTask>(); //holds the timers for sent packets
    private Hashtable<Integer, TCPPacket> packetList = new Hashtable<Integer,TCPPacket>();	  //holds the packets associated with each timer
      //(for timer identification)
    private boolean wantsToClose = false;
    private boolean finSent = false;

    private static final int CLOSED = 0;
    private static final int SYN_SENT = 1;
    private static final int LISTEN = 2;
    private static final int SYN_RCVD = 3;
    private static final int ESTABLISHED = 4;
    private static final int FIN_WAIT_1 = 5;
    private static final int FIN_WAIT_2 = 6;
    private static final int CLOSING = 7;
    private static final int CLOSE_WAIT = 8;
    private static final int LAST_ACK = 9;
    private static final int TIME_WAIT = 10;

    private Demultiplexer D;
    private Timer tcpTimer;

    private PipedOutputStream appOS;
    private PipedInputStream appIS;

    private PipedInputStream pipeAppToSocket;
    private PipedOutputStream pipeSocketToApp;

    private SocketReader reader;
    private SocketWriter writer;

    private boolean terminating = false;

    private InfiniteBuffer sendBuffer;
    // private InfiniteBuffer recvBuffer = new InfiniteBuffer();
    private InfiniteBuffer recvBuffer;


    StudentSocketImpl(Demultiplexer D) {  // default constructor
        this.D = D;

        try {
            pipeAppToSocket = new PipedInputStream();
            pipeSocketToApp = new PipedOutputStream();

            appIS = new PipedInputStream(pipeSocketToApp);
            appOS = new PipedOutputStream(pipeAppToSocket);
        }
        catch(IOException e){
            System.err.println("unable to create piped sockets");
            System.exit(1);
        }

        initBuffers();

        reader = new SocketReader(pipeAppToSocket, this);
        reader.start();

        writer = new SocketWriter(pipeSocketToApp, this);
        writer.start();
    }


    /**
    * initialize buffers and set up sequence numbers
    */
    private void initBuffers(){
        //done elsewhere
    }


    /**
    * Called by the application-layer code to copy data out of the
    * recvBuffer into the application's space.
    * Must block until data is available, or until terminating is true
    * @param buffer array of bytes to return to application
    * @param length desired maximum number of bytes to copy
    * @return number of bytes copied (by definition > 0)
    */
    synchronized int getData(byte[] buffer, int length){
        System.out.println("getData()------------");
        //wait for packet to be received
        int counter = 0;
        byte[] data = null;
        int temp = 0;
        if(recvBuffer == null){
            try{
                System.out.print("wait");
                this.wait();
            }
            catch(InterruptedException e){
                System.err.println("Error occured when trying to wait.");
            }
        }
        else{
            System.out.println("Length: " + length + " ,recBuffer.getBufferSize(): " + recvBuffer.getBufferSize());
            data = new byte[recvBuffer.getBufferSize()];
            recvBuffer.copyOut(buffer, 0, recvBuffer.getBufferSize());
            recvBuffer.advance(recvBuffer.getBufferSize());
            String bufferString = "";
            data = buffer;

            // for(int i = 0; i < length-1 && data[i+1] == (byte)'0'; i++){
            //     System.out.println((char)data[i] + ",i: " + i);
            //     bufferString += (char)data[i];
            //     counter++;
            // }
            // System.out.println("recvBuffer data: " + bufferString);
            // System.out.println("counter #: " + counter);
            // recvBuffer = null;
            System.out.println("buffer.length= "+recvBuffer.getBufferSize());
            temp = recvBuffer.getBufferSize();
            recvBuffer = null;
        }
        return temp;
    }


    /**
    * accept data written by application into sendBuffer to send.
    * Must block until ALL data is written.
    * @param buffer array of bytes to copy into app
    * @param length number of bytes to copy
    */
    synchronized void dataFromApp(byte[] buffer, int length){
        System.out.println("dataFromApp()--------");

        sendBuffer = new InfiniteBuffer(length);
        sendBuffer.append(buffer, 0, length);
        sendData(length);
    }

    /**
     * Use sendBuffer data and send packets
     */
    private void sendData(int length){
        System.out.println("sendData()--------");
        double numPacketToSend = 0;
        byte[] data = new byte[length];
        sendBuffer.copyOut(data, sendBuffer.getBase(), length);
        sendBuffer.advance(length);
        String bufferString = "";
        int i = 0, j;
        int numSent = 0;

        do{
            j = 0;
            byte[] sendData;

            if(data.length - numSent*1000 < 1000){
                sendData = new byte[data.length - numSent*1000];
            }else{
                numPacketToSend = Math.ceil((double)data.length / 1000.0);
                sendData = new byte[1000];
            }

            for(i = i+(numSent*1000); i < data.length && i < 1000+(1000*numSent); i++){
                    bufferString += (char)data[i];
                    sendData[j] = data[i];
                    j++;
                }

                TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, sendData);
                incrementCounters(ackPacket);
                sendPacket(ackPacket, false);
                Arrays.fill(sendData, (byte)'0');
                numPacketToSend--;numSent++;i=0;
        }
        while(numPacketToSend > 0);
            //Step 2, retrieving info from sendBuffer and printing
            byte[] data = new byte[3];
            sendBuffer.copyOut(data, 0, 3);
            String bufferString = "";
            System.out.println("\\/\\/\\/Send Data\\/\\/\\/");
            for(int i = 0; i < data.length; i++){
                bufferString += (char)data[i];
            }
            System.out.println(bufferString);
            System.out.println("\n /\\/\\/\\End SendData/\\/\\/\\ ");
    }

    /**
    * Connects this socket to the specified port number on the specified host.
    *
    * @param      address   the IP address of the remote host.
    * @param      port      the port number.
    * @exception  IOException  if an I/O error occurs when attempting a
    *               connection.
    */
    public synchronized void connect(InetAddress address, int port) throws IOException{
        //client state
        localport = D.getNextAvailablePort();

        this.address = address;
        this.port = port;

        D.registerConnection(address, localport, port, this);

        seqNum = 100;
        TCPPacket synPacket = new TCPPacket(localport, port, seqNum, ackNum, false, true, false, 1, null);
        changeToState(SYN_SENT);
        sendPacket(synPacket, false);
    }

    /**
    * Called by Demultiplexer when a packet comes in for this connection
    * @param p The packet that arrived
    */
    public synchronized void receivePacket(TCPPacket p){
    	this.notifyAll();

        System.out.println("Packet received from address " + p.sourceAddr + " with seqNum " + p.seqNum + " is being processed.");

        String bufferString = "";
        byte[] data = p.getBufferPacket();
        byte[] data_noHead = new byte[data.length-20];
        for(int i = 20; i < data.length; i++){
            bufferString += (char)data[i];
            data_noHead[i-20] = data[i];
        }

        System.out.println("Packet data is: " + bufferString);

        if(p.getData() != null && p.seqNum == ackNum){
            recvBuffer = new InfiniteBuffer(p.getBufferPacket().length - 20);
            recvBuffer.append(data_noHead, 0, p.getBufferPacket().length-20);
            this.notifyAll();
        }

        System.out.print("The packet is ");
        if(p.ackFlag == true && p.synFlag == true){
            System.out.println("a syn-ack.");

            if(state == SYN_SENT){
                //client state
                incrementCounters(p);
                cancelPacketTimer();
                TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
                changeToState(ESTABLISHED);
                sendPacket(ackPacket, false);
            }
            else if (state == ESTABLISHED){
                //client state, strange message due to packet loss
                incrementCounters(p);
                TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
                sendPacket(ackPacket, false);
            }
            else if (state == FIN_WAIT_1){
                //client state, strange message due to packet loss
                incrementCounters(p);
                TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
                sendPacket(ackPacket, false);
            }
        }
        else if(p.ackFlag == true){
            System.out.println("an ack.");
            //for the love of God, do not incrementCounters(p) in here

            if(state == SYN_RCVD){
                //server state
                cancelPacketTimer();
                changeToState(ESTABLISHED);
            }
            else if(state == FIN_WAIT_1){
                //client state
                cancelPacketTimer();
                changeToState(FIN_WAIT_2);
            }
            else if(state == LAST_ACK){
                //server state
                cancelPacketTimer();
                changeToState(TIME_WAIT);
            }
            else if(state == CLOSING){
                //client or server state
                cancelPacketTimer();
                changeToState(TIME_WAIT);
            }
        }
        else if(p.synFlag == true){
            System.out.println("a syn.");

            if(state == LISTEN){
                //server state
                try{
                    D.unregisterListeningSocket(localport, this);	                     //***********tricky*************
                    D.registerConnection(p.sourceAddr, p.destPort, p.sourcePort, this); //***********tricky*************
                }
                catch(IOException e){
                    System.out.println("Error occured while attempting to establish connection");
                }

                this.address = p.sourceAddr;
                this.port = p.sourcePort;

                incrementCounters(p);
                TCPPacket synackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, true, false, 1, null);
                changeToState(SYN_RCVD);
                sendPacket(synackPacket, false);
            }
        }
        else if(p.finFlag == true){
            System.out.println("a fin.");

            if(state == ESTABLISHED){
                //server state
                incrementCounters(p);
                TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
                changeToState(CLOSE_WAIT);
                sendPacket(ackPacket, false);
            }
            else if(state == FIN_WAIT_1){
                //client state or server state
                incrementCounters(p);
                TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
                changeToState(CLOSING);
                sendPacket(ackPacket, false);
            }
            else if(state == FIN_WAIT_2){
                //client state
                incrementCounters(p);
                TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
                changeToState(TIME_WAIT);
                sendPacket(ackPacket, false);
            }
            else if(state == LAST_ACK){
                //server state, strange message due to packet loss
                TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
                sendPacket(ackPacket, false);
            }
            else if(state == CLOSING){
                //client or server state, strange message due to packet loss
                TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
                sendPacket(ackPacket, false);
            }
            else if(state == TIME_WAIT){
                //client or server state, strange message due to packet loss
                TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
                sendPacket(ackPacket, false);
            }
        }
        else{
            System.out.println("a chunk of data.");
        }
    }//receivePacket

    /**
    * Waits for an incoming connection to arrive to connect this socket to
    * Ultimately this is called by the application calling
    * ServerSocket.accept(), but this method belongs to the Socket object
    * that will be returned, not the listening ServerSocket.
    * Note that localport is already set prior to this being called.
    */
    public synchronized void acceptConnection() throws IOException {
        //server state
        changeToState(LISTEN);

        D.registerListeningSocket (localport, this);

        seqNum = 10000;

        try{
            this.wait();
        }
        catch(InterruptedException e){
            System.err.println("Error occured when trying to wait.");
        }
    }//acceptConnection


    /**
    * Returns an input stream for this socket.  Note that this method cannot
    * create a NEW InputStream, but must return a reference to an
    * existing InputStream (that you create elsewhere) because it may be
    * called more than once.
    *
    * @return     a stream for reading from this socket.
    * @exception  IOException  if an I/O error occurs when creating the
    *               input stream.
    */
    public InputStream getInputStream() throws IOException {return appIS;}

    /**
    * Returns an output stream for this socket.  Note that this method cannot
    * create a NEW InputStream, but must return a reference to an
    * existing InputStream (that you create elsewhere) because it may be
    * called more than once.
    *
    * @return     an output stream for writing to this socket.
    * @exception  IOException  if an I/O error occurs when creating the
    *               output stream.
    */
    public OutputStream getOutputStream() throws IOException {return appOS;}


    /**
    * Closes this socket.
    *
    * @exception  IOException  if an I/O error occurs when closing this socket.
    */
    public synchronized void close() throws IOException {
        System.out.println("*** close() was called by the application.");

        if(address==null)
            return;

            terminating = true;

            while(!reader.tryClose()){
            notifyAll();
            try{
                wait(1000);
            }
            catch(InterruptedException e){}
        }
        writer.close();

        notifyAll();

        if(state == ESTABLISHED){
            //client state
            TCPPacket finPacket = new TCPPacket(localport, port, seqNum, ackNum, false, false, true, 1, null);
            changeToState(FIN_WAIT_1);
            sendPacket(finPacket, false);
            finSent = true;
        }
        else if(state == CLOSE_WAIT){
            //server state
            TCPPacket finPacket = new TCPPacket(localport, port, seqNum, ackNum, false, false, true, 1, null);
            changeToState(LAST_ACK);
            sendPacket(finPacket, false);
            finSent = true;
        }
        else{
            System.out.println("Attempted to close while not established (ESTABLISHED) or waiting to close (CLOSE_WAIT)");
            //timer task here... try the closing process again
            wantsToClose = true;
        }
        //returns immediately to the application

        // if(address==null)
        //     return;
        //
        //     terminating = true;
        //
        //     while(!reader.tryClose()){
        //     notifyAll();
        //     try{
        //         wait(1000);
        //     }
        //     catch(InterruptedException e){}
        // }
        // writer.close();
        //
        // notifyAll();
    }

    /**
    * create TCPTimerTask instance, handling tcpTimer creation
    * @param delay time in milliseconds before call
    * @param ref generic reference to be returned to handleTimer
    */
    private TCPTimerTask createTimerTask(long delay, Object ref){
        if(tcpTimer == null)
            tcpTimer = new Timer(false);

        return new TCPTimerTask(tcpTimer, delay, this, ref);
    }


    /**
    * handle timer expiration (called by TCPTimerTask)
    * @param ref Generic reference that can be used by the timer to return
    * information.
    */
    public synchronized void handleTimer(Object ref){
        if(ref == null){
            // this must run only once the last timer (30 second timer) has expired
            tcpTimer.cancel();
            tcpTimer = null;

            try{
                D.unregisterConnection(address, localport, port, this);
            }
            catch(IOException e){
                System.out.println("Error occured while attempting to close connection");
            }
        }
        else{	//its a packet that needs to be resent
            System.out.println("XXX Resending Packet");
            sendPacket((TCPPacket)ref, true);
        }
        /*
        // this must run only once the last timer (30 second timer) has expired
        tcpTimer.cancel();
        tcpTimer = null;*/
    }

    private String stateString(int inState){
        if(inState == 0){
            return "CLOSED";
        }
        else if(inState == 1){
            return "SYN_SENT";
        }
        else if(inState == 2){
            return "LISTEN";
        }
        else if(inState == 3){
            return "SYN_RCVD";
        }
        else if(inState == 4){
            return "ESTABLISHED";
        }
        else if(inState == 5){
            return "FIN_WAIT_1";
        }
        else if(inState == 6){
            return "FIN_WAIT_2";
        }
        else if(inState == 7){
            return "CLOSING";
        }
        else if(inState == 8){
            return "CLOSE_WAIT";
        }
        else if(inState == 9){
            return "LAST_ACK";
        }
        else if(inState == 10){
            return "TIME_WAIT";
        }
        else
            return "Invalid state number";
    }

    private synchronized void changeToState(int newState){
        System.out.println(stateString(state) + "->" + stateString(newState));
        state = newState;

        if(newState == CLOSE_WAIT && wantsToClose && !finSent){
            try{
                close();
            }
            catch(IOException ioe){}
        }
        else if(newState == TIME_WAIT){
            createTimerTask(30000, null);
        }
    }

    private synchronized void sendPacket(TCPPacket inPacket, boolean resend){
        if(inPacket.ackFlag == true && inPacket.synFlag == false){
            inPacket.seqNum = -2;
        }

        if(resend == false){ //new timer, and requires the current state as a key
            TCPWrapper.send(inPacket, address);

            //only do timers for syns, syn-acks, and fins
            if(inPacket.synFlag == true || inPacket.finFlag == true){
                System.out.println("Creating new TimerTask at state " + stateString(state));
                System.out.println("timerList: " + timerList.isEmpty());
                timerList.put(new Integer(state),createTimerTask(1000, inPacket));
                packetList.put(new Integer(state), inPacket);
            }
        }
        else{ //the packet is for resending, and requires the original state as the key
            Enumeration keyList = timerList.keys();
            Integer currKey = new Integer(-1);
            try{
                for(int i = 0; i<10; i++){
                    currKey = (Integer)keyList.nextElement();

                    if(packetList.get(currKey) == inPacket){
                        System.out.println("Recreating TimerTask from state " + stateString(currKey));
                        TCPWrapper.send(inPacket, address);
                        timerList.put(currKey,createTimerTask(1000, inPacket));
                        break;
                    }
                }
            }
            catch(NoSuchElementException nsee){}
        }

        if(inPacket.getData() != null){
            dataFromApp(inPacket.getData(), inPacket.getData().length);
        }
    }

    private synchronized void incrementCounters(TCPPacket p){
        System.out.println("---------------");
        System.out.println(p.seqNum);
        System.out.println(p.ackNum);
        System.out.println("---------------");

        ackNum = p.seqNum + 1;

        if(p.ackNum != -1)
            seqNum = p.ackNum;
    }

    private synchronized void cancelPacketTimer(){
        //must be called before changeToState is called!!!

        if(state != CLOSING){
            timerList.get(state).cancel();
            timerList.remove(state);
            packetList.remove(state);
        }
        else{
            //the only time the state changes before an ack is received... so it must
            //look back to where the fin timer started
            timerList.get(FIN_WAIT_1).cancel();
            timerList.remove(FIN_WAIT_1);
            packetList.remove(FIN_WAIT_1);
        }
    }

}
