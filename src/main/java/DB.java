import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DB {
    private final String url = "postgres://kgtudgtcfazgwh:10f3b7de6744921595f2c5d6443d0ba0f73cdc8254fdbfaa5313ed6507ba9e52@ec2-52-214-125-106.eu-west-1.compute.amazonaws.com:5432/d2j6bj1jao3ldi";
    private final String user = "atjlyfnvblurju";
    private final String password = "adfcc3481cd6f12b56c3eb6f33f30c5ccd34ed43450cf6e426643db6a173337a";
/*
    private final String url = "jdbc:postgresql://localhost/quizent";
    private final String user = "postgres";
    private final String password = "1111";
*/
    public Connection connect() {
/*
        Connection conn = null;
        try {
            conn = DriverManager.getConnection(url, user, password);
            System.out.println("Connected to the PostgreSQL server successfully.");
        } catch (SQLException e) {
            System.out.println(e.getMessage());
        }
        return conn;
*/
        Connection conn = null;
        URI dbUri = null;
        try {
            dbUri = new URI(url);
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        String username = dbUri.getUserInfo().split(":")[0];
        String password = dbUri.getUserInfo().split(":")[1];
        String dbUrl = "";
        dbUrl = "jdbc:postgresql://" + dbUri.getHost() + ':' + dbUri.getPort() + dbUri.getPath() + "?sslmode=require";
        try {
            conn = DriverManager.getConnection(dbUrl, username, password);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return conn;
    }
}
