import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.util.Callback;

import java.io.*;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class Controller implements Initializable {

    @FXML
    VBox authPanel, cloudPanel, clientPanel, serverPanel;

    @FXML
    TextField usernameField, clientPathField, serverPathField;

    @FXML
    PasswordField passwordField;

    @FXML
    ComboBox<String> disksBox;

    @FXML
    ListView<FileInfo> clientFilesList, serverFilesList;

    @FXML
    CheckBox isNewUserCheckBox;

    @FXML
    Button authButton;

    Socket socket;
    private static final String HOST = "localhost";
    private static final int PORT = 8585;
    private String username;

    private static final byte OK = 0;
    private static final byte ERROR = 1;
    private static final byte DEFAULT = -1;
    private static final byte COMMAND_AUTH = 0;
    private static final byte COMMAND_SEND_FILE = 1;
    private static final byte COMMAND_DOWNLOAD_FILE = 2;
    private static final byte COMMAND_DELETE_SERVER_FILE = 4;
    private static final byte COMMAND_LIST_SERVER_FILES = 5;

    DataInputStream in;
    DataOutputStream out;

    private byte isNewUser = 0;
    private byte status = DEFAULT;

    Path path;
    Path defaultClientPath = Paths.get("client");


    @Override
    public void initialize(URL location, ResourceBundle resources) {
        setMyCellFactory(clientFilesList);
        setMyCellFactory(serverFilesList);
        disksBox.getItems().clear();
        for (Path p : FileSystems.getDefault().getRootDirectories()) {
            disksBox.getItems().add(p.toString());
        }
        disksBox.getSelectionModel().select(0);
    }

    private void connect() {
        try {
            socket = new Socket(HOST, PORT);
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());
        } catch (IOException e) {
            showErrorAlert("Не удалось установить связь с облачным хранилищем");
        }
    }

    public void setNewUser() {
        if (isNewUserCheckBox.isSelected()) {
            authButton.setText("Зарегистрироваться");
            isNewUser = 1;
        } else {
            authButton.setText("Войти");
            isNewUser = 0;
        }
    }

    public void authorization() {
        byte answerCode;

        if(socket == null || socket.isClosed()) {
            connect();
        }

        try {
            username = usernameField.getText();
            String password = passwordField.getText();
            int usernameLength = username.getBytes("UTF-8").length;
            int passwordLength = password.getBytes("UTF-8").length;
            ByteBuffer bufAuth = ByteBuffer.allocate(1 + 1 + 4 + usernameLength + 4 + passwordLength);
            bufAuth.put(COMMAND_AUTH);
            bufAuth.put(isNewUser);
            bufAuth.putInt(usernameLength);
            bufAuth.put(username.getBytes("UTF-8"));
            bufAuth.putInt(passwordLength);
            bufAuth.put(password.getBytes("UTF-8"));
            bufAuth.flip();
            byte[] bytesBufAuth = new byte[bufAuth.remaining()];
            bufAuth.get(bytesBufAuth);
            out.write(bytesBufAuth);
            bufAuth.clear();
            status = OK;
        } catch (IOException e) {
            status = ERROR;
            showErrorAlert("Не удалось авторизоваться");
        }

        if (status == OK) {
            try {
                while (in.available() < 1) {
                }
                answerCode = in.readByte();
                if (answerCode == OK) {
                    usernameField.clear();
                    passwordField.clear();
                    if(isNewUserCheckBox.isSelected()) {
                        isNewUserCheckBox.fire();
                    }
                    setAuthorized(true);
                    disksBox.getSelectionModel().select(0);
                    goToPathClient(defaultClientPath);
                    updateServerFilesList();
                } else if (answerCode == ERROR) {
                    if (isNewUser == 1) {
                        showErrorAlert("Такой пользователь уже существует");
                    } else if (isNewUser == 0) {
                        showErrorAlert("Такой пользователь не найден");
                    }
                }
            } catch (IOException e) {
                showErrorAlert("Не удалось получить ответ от сервера");
            }
        }
    }

    public void setAuthorized(boolean isAuthorized) {
        if(!isAuthorized) {
            authPanel.setVisible(true);
            authPanel.setManaged(true);
            cloudPanel.setVisible(false);
            cloudPanel.setManaged(false);
        } else {
            authPanel.setVisible(false);
            authPanel.setManaged(false);
            cloudPanel.setVisible(true);
            cloudPanel.setManaged(true);
        }
    }

    public List<FileInfo> getFilesClient(Path path) throws IOException {
        return Files.list(path).map(FileInfo::new).collect(Collectors.toList());
    }

    public List<FileInfo> getFilesServer() {
        status = DEFAULT;
        List<FileInfo> filesList = new ArrayList<>();
        try {
            out.write(COMMAND_LIST_SERVER_FILES);
            status = OK;
        } catch (IOException e) {
            status = ERROR;
            showErrorAlert("Не удалось запросить список файлов");
        }

        if (status == OK) {
            try {
                while (in.available() < 1) {
                }
                byte answerCode = in.readByte();
                if (answerCode == OK) {
                    int filesCount = in.readInt();
                    for (int i = 0; i < filesCount; i++) {
                        int fileNameLength = in.readInt();
                        byte[] fileNameBytes = new byte[fileNameLength];
                        in.read(fileNameBytes);
                        String fileName = new String(fileNameBytes, "UTF-8");
                        long fileLength = in.readLong();
                        filesList.add(new FileInfo(fileName, fileLength));
                    }
                }
            } catch (IOException e) {
                status = ERROR;
                showErrorAlert("Не удалось получить список файлов");
            }
        }
        return filesList;
    }

    public void goToPathClient(Path newPath) throws IOException {
        path = newPath;
        clientPathField.setText(path.toAbsolutePath().toString());
        clientFilesList.getItems().clear();
        clientFilesList.getItems().add(new FileInfo("[..]", -2L));
        clientFilesList.getItems().addAll(getFilesClient(path));
        clientFilesList.getItems().sort((o1, o2) -> {
            if (o1.getFileName().equals("[..]")) {
                return -1;
            }
            if ((int)Math.signum(o1.getLength()) == (int)Math.signum(o2.getLength())) {
                return o1.getFileName().compareTo(o2.getFileName());
            }
            return new Long(o1.getLength() - o2.getLength()).intValue();
        });
    }

    public void updateClientFilesList() {
        try {
            goToPathClient(path);
        } catch (IOException e) {
            showErrorAlert("Не удалось обновить список файлов");
        }
    }

    public void updateServerFilesList() {
        serverPathField.setText("server/" + username);
        serverFilesList.getItems().clear();
        serverFilesList.getItems().addAll(getFilesServer());
        serverFilesList.getItems().sort(Comparator.comparing(FileInfo::getFileName));
    }

    public void selectDiskAction(ActionEvent actionEvent) {
        try {
            ComboBox<String> element = (ComboBox<String>) actionEvent.getSource();
            goToPathClient(Paths.get(element.getSelectionModel().getSelectedItem()));
        } catch (IOException e) {
            showErrorAlert("Не удалось обновить список файлов");
        }
    }

    public void clientFilesListClicked(MouseEvent mouseEvent) throws IOException {
        if (mouseEvent.getClickCount() == 2) {
            FileInfo fileInfo = clientFilesList.getSelectionModel().getSelectedItem();
            if (fileInfo != null) {
                if (fileInfo.isDirectory()) {
                    Path pathTo = path.resolve(fileInfo.getFileName());
                    goToPathClient(pathTo);
                }
                if (fileInfo.isUpElement()) {
                    Path pathTo = path.toAbsolutePath().getParent();
                    if (pathTo != null) {
                        goToPathClient(pathTo);
                    }
                }
            }
        }
    }

    public void sendBtnAction() {
        status = DEFAULT;
    	FileInfo fileInfo = clientFilesList.getSelectionModel().getSelectedItem();
    	if (fileInfo == null || fileInfo.isDirectory() || fileInfo.isUpElement()) {
    		showErrorAlert("Файл не выбран");
    		return;
    	}
    	Path selectedClientFile = path.resolve(fileInfo.getFileName());
        try {
            int fileNameLength = selectedClientFile.getFileName().toString().getBytes("UTF-8").length;
            ByteBuffer bufSendFileInfo = ByteBuffer.allocate(1 + 4 + fileNameLength + 8);
            bufSendFileInfo.put(COMMAND_SEND_FILE);
            bufSendFileInfo.putInt(fileNameLength);
            bufSendFileInfo.put(selectedClientFile.getFileName().toString().getBytes("UTF-8"));
            bufSendFileInfo.putLong(Files.size(selectedClientFile));
            bufSendFileInfo.flip();
            byte[] bytesBufSendFileInfo = new byte[bufSendFileInfo.remaining()];
            bufSendFileInfo.get(bytesBufSendFileInfo);
            byte[] bytesBufSendFileContent = Files.readAllBytes(selectedClientFile);
            out.write(bytesBufSendFileInfo);
            out.write(bytesBufSendFileContent);
            bufSendFileInfo.clear();
            status = OK;
        } catch (IOException e) {
            status = ERROR;
            showErrorAlert("Указанный файл не найден");
        }

        if (status == OK) {
            try {
                while (in.available() < 1) {
                }
                byte answerCode = in.readByte();
                if (answerCode == ERROR) {
                    showErrorAlert("Не удалось отправить файл");
                }
            } catch (IOException e) {
                status = ERROR;
                showErrorAlert("Не удалось получить ответ");
            }
        }
    	selectedClientFile = null;
    	updateServerFilesList();
    }

    public void deleteOnClientBtnAction() {
    	FileInfo fileInfo = clientFilesList.getSelectionModel().getSelectedItem();
    	if (fileInfo == null || fileInfo.isDirectory() || fileInfo.isUpElement()) {
    		showErrorAlert("Файл не выбран");
    		return;
    	}
        try {
            Files.delete(path.resolve(fileInfo.getFileName()));
        } catch (IOException e) {
            showErrorAlert("Не удалось удалить файл");
        }
        updateClientFilesList();
    }

    public void downloadBtnAction() {
    	FileInfo fileInfo = serverFilesList.getSelectionModel().getSelectedItem();
    	if (fileInfo == null || fileInfo.isDirectory() || fileInfo.isUpElement()) {
    		showErrorAlert("Файл не выбран");
    		return;
    	}
    	String fileName = fileInfo.getFileName();
        status = DEFAULT;

        try {
            int fileNameLength = fileName.getBytes("UTF-8").length;
            ByteBuffer bufDownloadFileInfo = ByteBuffer.allocate(1 + 4 + fileNameLength);
            bufDownloadFileInfo.put(COMMAND_DOWNLOAD_FILE);
            bufDownloadFileInfo.putInt(fileNameLength);
            bufDownloadFileInfo.put(fileName.getBytes("UTF-8"));
            bufDownloadFileInfo.flip();
            byte[] bytesBufDownloadFileInfo = new byte[bufDownloadFileInfo.remaining()];
            bufDownloadFileInfo.get(bytesBufDownloadFileInfo);
            out.write(bytesBufDownloadFileInfo);
            bufDownloadFileInfo.clear();
            status = OK;
        } catch (IOException e) {
            status = ERROR;
            showErrorAlert("Не удалось отправить информацию на сервер");
        }

        if (status == OK) {
            try {
                while (in.available() < 1) {
                }
                byte answerCode = in.readByte();
                if (answerCode == OK) {
                    in.readLong();
                    try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(path + "/" + fileName))) {
                        while (in.available() > 0) {
                            out.write(in.readByte());
                        }
                    }
                } else if (answerCode == ERROR) {
                    showErrorAlert("Не удалось скачать файл");
                }
            } catch (IOException e) {
                status = ERROR;
                showErrorAlert("Не удалось скачать файл");
            }
        }
    	updateClientFilesList();
    }

    public void deleteOnServerBtnAction() {
        status = DEFAULT;
    	FileInfo fileInfo = serverFilesList.getSelectionModel().getSelectedItem();
    	if (fileInfo == null || fileInfo.isDirectory() || fileInfo.isUpElement()) {
    		showErrorAlert("Файл не выбран");
    		return;
    	}
    	String fileName = fileInfo.getFileName();

        try {
            int fileNameLength = fileName.getBytes("UTF-8").length;
            ByteBuffer bufDeleteFileInfo = ByteBuffer.allocate(1 + 4 + fileNameLength);
            bufDeleteFileInfo.put(COMMAND_DELETE_SERVER_FILE);
            bufDeleteFileInfo.putInt(fileNameLength);
            bufDeleteFileInfo.put(fileName.getBytes("UTF-8"));
            bufDeleteFileInfo.flip();
            byte[] bytesBufDeleteFileInfo = new byte[bufDeleteFileInfo.remaining()];
            bufDeleteFileInfo.get(bytesBufDeleteFileInfo);
            out.write(bytesBufDeleteFileInfo);
            bufDeleteFileInfo.clear();
            status = OK;
        } catch (IOException e) {
            status = ERROR;
            showErrorAlert("Не удалось отправить информацию на сервер");
        }

        if (status == OK) {
            try {
                while (in.available() < 1) {
                }
                byte answerCode = in.readByte();
                if (answerCode == ERROR) {
                    showErrorAlert("Не удалось удалить файл");
                }
            } catch (IOException e) {
                status = ERROR;
                showErrorAlert("Не удалось удалить файл");
            }
        }
    	updateServerFilesList();
    }

    public void showErrorAlert(String message) {
    	Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
    	alert.showAndWait();
    }

    public void btnExitAction(ActionEvent actionEvent) {
        try {
            in.close();
            out.close();
            socket.close();
            setAuthorized(false);
            username = null;
        } catch (IOException e) {
            showErrorAlert("Не удалось корректно разорвать связь с облачным хранилищем");
        }
    }

    private void setMyCellFactory(ListView<FileInfo> filesList) {
        filesList.setCellFactory(new Callback<ListView<FileInfo>, ListCell<FileInfo>>() {
            @Override
            public ListCell<FileInfo> call(ListView<FileInfo> param) {
                return new ListCell<FileInfo>() {
                    @Override
                    protected void updateItem(FileInfo item, boolean isEmpty) {
                        super.updateItem(item, isEmpty);
                        if (item == null || isEmpty) {
                            setText(null);
                            setStyle("");
                        } else {
                            String formattedFileName = String.format("%-30s", item.getFileName());
                            String formattedFileLength = String.format("%,d bytes", item.getLength());
                            if(item.getLength() == -1L) {
                                formattedFileLength = String.format("%s", "DIR");
                            }
                            if(item.getLength() == -2L) {
                                formattedFileLength = "";
                            }
                            String text = String.format("%s %-20s", formattedFileName, formattedFileLength);
                            setText(text);
                        }
                    }
                };
            }
        });
    }

}