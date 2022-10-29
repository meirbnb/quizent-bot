import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DB {
    private final String url = "postgres://put_here_link_to_your_postgres_DB";
    private final String user = "admin";
    private final String password = "admin";
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
