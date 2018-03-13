package server;

import beans.Token;
import beans.User;
import com.google.gson.Gson;
import dao.BasicVirtualTable;
import handler.Command;
import handler.PipelineFactory;
import handler.RequestResult;
import handler.Utility;
import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Properties;

class Connection implements Runnable {

    private Socket socket;

    //Web directory definition
    public static String wwwDir = System.getProperty("user.dir") + "/www/";
    static {
        InputStream input = Connection.class.getClassLoader().getResourceAsStream("config.properties");
        if (input != null) {
            try {
                Properties properties = new Properties();
                properties.load(input);
                wwwDir = properties.getProperty("webDir");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    Connection(Socket socket) {
        this.socket = socket;
        new Thread(this).start();
    }

    // This the 'main' method the request is handled here the order of each call is very important
    public void run() {
        try {
            PrintStream out = new PrintStream(socket.getOutputStream());
            HttpReq requete = new HttpReq();

            requete.doParse(socket.getInputStream());

            // Préparation de la réponse
            HttpAns ans = new HttpAns();
            String path = requete.getPath().toLowerCase();

            if (requete.isGet()){
                boolean fileOK = false;
                if (path.equals("/"))
                    path = "/index.html";

                //ans.setType(getFileType(path));

                if (Files.exists(Paths.get(wwwDir + path))){
                    fileOK = true;
                    ans.setLen(Math.toIntExact(new File(wwwDir + path).length()));
                    ans.setType(Files.probeContentType(Paths.get(wwwDir + path)));
                }else {
                    ans.setCode(HttpAns._404);
                }

                sendHeader(out, ans);

                if (fileOK){
                    sendBinaryFileStream(new File(wwwDir + path), out);
                }

            } else if (requete.isPost()){
                // Handle API Request
                if (path.startsWith("/api")){
                    // Verify API Key
                    BasicVirtualTable<Token> tok = new BasicVirtualTable<>(Token.class);
                    String token = requete.getParameter("key");
                    if (tok.find(token) != null){
                        // Redirect to api
                        Gson gson = new Gson();
                        Command cmd = gson.fromJson("{ \"parameters\" : " + requete.getBody() + " }", Command.class);
                        try {
                            RequestResult result = PipelineFactory.getPipeline().handle(cmd);
                            //TODO Check if result is null (means nothing in the pipeline to handle the request)
                            ans.setCode(HttpAns._200).setType(HttpAns._json).setLen(result.toJson().length());
                            out.print(ans.build());
                            out.print("\n");
                            out.print(result.toJson());
                        }catch (Exception e){
                            // Exception thrown in the pipeline returning 500 error code to client
                            String err = "{\"error\":\"" + e.toString().replace("\n", "\t")+ "\"}";
                            ans.setCode(HttpAns._500).setLen(err.length()).setType(HttpAns._json);
                            out.print(ans.build() + "\n" + err);
                        }

                    }else {
                        // Api Key is not valid
                        String err = "{\"error\":\"Invalid API Key !\"}";
                        ans.setCode(HttpAns._403).setType(HttpAns._json).setLen(err.length());
                        out.print(ans.build() + "\n" + err);
                    }
                }else if (path.startsWith("/connect")){
                    //Handle connection
                }else if (path.startsWith("/create")){
                    handleAccountCreation(out, requete, ans);
                }else {
                    //Handle other post requests
                }
            }

            // Close connection
            socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void handleAccountCreation(PrintStream out, HttpReq requete, HttpAns ans) {
        HashMap<String, String> params = Utility.gson.fromJson(requete.getBody(), HashMap.class);
        String email = params.get("email");
        String nom = params.get("nom");
        String prenom = params.get("prenom");
        String password = params.get("password");

        System.out.printf("%s %s %s %s", email, nom, prenom, password);
        User jeanPierre = new User(email, nom, prenom, Utility.hashSHA256(password));

        (new BasicVirtualTable<User>(User.class)).add(jeanPierre);

        String body = "{\"status\":\"success\"}";

        ans.setType(HttpAns._json).setLen(body.length()).setCode(HttpAns._200);
        out.print(ans.build() + "\n" + body);
    }

    private static void sendHeader(PrintStream out, HttpAns ans) {
        out.print(ans.build());
        out.println();
    }

    private static void sendBinaryFileStream(File f, PrintStream out) {
        FileInputStream fis;
        try {
            // Java 9
            fis = new FileInputStream(f);
            fis.transferTo(out);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
