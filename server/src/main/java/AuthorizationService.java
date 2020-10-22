import io.netty.buffer.ByteBuf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;

public class AuthorizationService {

    private static Connection connection;
    private static Statement statement;
    private static final String ERROR = "ERROR";

    public static String getERROR() {
        return ERROR;
    }

    public static void connect() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:cloud_db.db");
            statement = connection.createStatement();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    public static String run(ByteBuf buf) throws SQLException, IOException {

        byte isNewUser = buf.readByte();
        System.out.println("Признак нового пользователя: " + isNewUser);

        int usernameLength = buf.readInt();
        System.out.println("Длина логина: " + usernameLength);

        byte[] usernameBytes = new byte[usernameLength];
        buf.readBytes(usernameBytes);
        String username = new String(usernameBytes, "UTF-8");
        System.out.println("Логин: " + username);

        int passwordLength = buf.readInt();
        System.out.println("Длина пароля: " + passwordLength);

        byte[] passwordBytes = new byte[passwordLength];
        buf.readBytes(passwordBytes);
        String password = new String(passwordBytes, "UTF-8");
        System.out.println("Пароль: " + password);

        buf.release();

        String answer;
        if (isNewUser == 1) {
            String sql = String.format("SELECT user_id FROM user WHERE username = '%s';", username);
            ResultSet rs = statement.executeQuery(sql);
            if (rs.next()) {
                answer = ERROR;
            } else {
                PreparedStatement pstmt = connection.prepareStatement("INSERT INTO user (username, password) VALUES (?, ?);");
                pstmt.setString(1, username);
                pstmt.setString(2, password);
                pstmt.executeUpdate();
                Files.createDirectory(Paths.get("server/" + username));
                answer = username;
            }
        } else {
            String sql = String.format("SELECT user_id FROM user WHERE username = '%s' AND password = '%s';", username, password);
            ResultSet rs = statement.executeQuery(sql);
            if (rs.next()) {
                answer = username;
            } else {
                answer = ERROR;
            }
        }

        return answer;
    }

    public static void disconnect() {
        try {
            connection.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
