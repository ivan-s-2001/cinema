package app.cinemahall.tv;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** One bounded local JSON endpoint for both clients. No LAN binding, API credentials or arbitrary URLs. */
final class CinemaServer {
    static String base(Context context){return "http://127.0.0.1:"+port(context);}
    static int port(Context context){return context.getPackageName().contains("mobile")?18992:18991;}
    private final int port;
    private final CinemaCatalog catalog;
    private final ExecutorService requests=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"cinemahall-request");t.setDaemon(true);return t;});
    CinemaServer(Context context){port=port(context);Net.ENGINE_PORT=port==18992?18993:18990;Net.ENGINE="http://127.0.0.1:"+Net.ENGINE_PORT;catalog=new CinemaCatalog(context);}
    void start(){Thread thread=new Thread(()->{
        try(ServerSocket listener=new ServerSocket(port,16,InetAddress.getByName("127.0.0.1"))){
            while(!Thread.currentThread().isInterrupted()){
                Socket socket=listener.accept();try{requests.execute(()->serve(socket));}catch(RejectedExecutionException full){socket.close();}
            }
        }catch(IOException error){android.util.Log.e("CinemaHall","Local catalogue could not start",error);}
    },"cinemahall-listener");thread.setDaemon(true);thread.start();}
    private void serve(Socket socket){
        try(Socket connection=socket){
            connection.setSoTimeout(5000);InputStream in=connection.getInputStream();ByteArrayOutputStream headers=new ByteArrayOutputStream();int state=0;
            while(headers.size()<8192){int c=in.read();if(c<0)return;headers.write(c);state=(state==0||state==2)?(c=='\r'?state+1:0):(c=='\n'?state+1:0);if(state==4)break;}
            if(state!=4){send(connection,431,"text/plain","Request too large",null);return;}
            String raw=headers.toString("US-ASCII");String[] first=raw.split("\r\n",2)[0].split(" ");
            if(first.length!=3||!first[0].equals("GET")){send(connection,405,"text/plain","GET only",null);return;}
            // Browser pages must not cause playback or probe app-private configuration.
            if(raw.toLowerCase(java.util.Locale.ROOT).contains("\r\norigin:")){send(connection,403,"text/plain","Origin denied",null);return;}
            URI uri=new URI(first[1]);if(uri.isAbsolute()){send(connection,400,"text/plain","Invalid request",null);return;}
            try{
                CinemaCatalog.Reply reply=catalog.route(uri.getRawPath());
                send(connection,reply.location==null?200:302,"application/json; charset=utf-8",reply.body,reply.location);
            }catch(Exception error){send(connection,503,"application/json; charset=utf-8","{\"error\":\"Источник временно недоступен\"}",null);}
        }catch(Exception ignored){}
    }
    private static void send(Socket socket,int code,String mime,String value,String location) throws IOException {
        byte[] body=value.getBytes(StandardCharsets.UTF_8);
        String headers="HTTP/1.1 "+code+" "+(code==200?"OK":code==302?"Found":"Error")+"\r\nContent-Type: "+mime+"\r\nContent-Length: "+body.length+"\r\nCache-Control: no-store\r\nConnection: close\r\n"+(location==null?"":"Location: "+location+"\r\n")+"\r\n";
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);
    }
}
