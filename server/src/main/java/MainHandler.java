import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class MainHandler extends ChannelInboundHandlerAdapter {

    private static final List<Channel> channels = new ArrayList<>();
    private static int clientIndex = 0;
    private String clientName;
    private String username;
    private static final byte STATE_OK = 0;
    private static final byte STATE_ERROR = 1;
    private static final byte COMMAND_UNDEFINED = -1;
    private static final byte COMMAND_AUTH = 0;
    private static final byte COMMAND_UPLOAD_FILE = 1;
    private static final byte COMMAND_DOWNLOAD_FILE = 2;
    private static final byte COMMAND_DELETE_FILE = 4;
    private static final byte COMMAND_GET_LIST_FILES = 5;
    private byte command = COMMAND_UNDEFINED;
    private enum StateUpload {
        NAME_LENGTH, NAME, FILE_LENGTH, FILE
    }
    private StateUpload currentState = StateUpload.NAME_LENGTH;
    private int fileUploadNameLength = -1;
    private long fileUploadLength = -1L;
    private long receivedFileUploadLength = 0L;
    private String fileUploadName;


    public static byte getStateOk() {
        return STATE_OK;
    }

    public static byte getStateError() {
        return STATE_ERROR;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        channels.add(ctx.channel());
        clientIndex++;
        clientName = "Клиент #" + clientIndex;
        System.out.println("Подключился " + clientName);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        System.out.println(clientName + " отключился");
        channels.remove(ctx.channel());
        ctx.close();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {

        ByteBuf buf = ((ByteBuf) msg);
        if(command == COMMAND_UNDEFINED) {
            command = buf.readByte();
        }
        System.out.println("Код команды: " + command);
        if (command == COMMAND_AUTH) {
            ByteBuf bufAnswer = ByteBufAllocator.DEFAULT.directBuffer(1);
            String answer = AuthorizationService.run(buf);
            if (answer.equals(AuthorizationService.getERROR())) {
                bufAnswer.writeByte(STATE_ERROR);
            } else {
                username = answer;
                bufAnswer.writeByte(STATE_OK);
            }
            command = COMMAND_UNDEFINED;
            ctx.writeAndFlush(bufAnswer);
        } else if (command == COMMAND_UPLOAD_FILE) {
            ctx.writeAndFlush(uploadFile(buf));
        } else if (command == COMMAND_DOWNLOAD_FILE) {
            ctx.writeAndFlush(downloadFile(buf));
        } else if (command == COMMAND_DELETE_FILE) {
            ctx.writeAndFlush(deleteFile(buf));
        } else if (command == COMMAND_GET_LIST_FILES) {
            ctx.writeAndFlush(getListFiles(buf));
        } else {
            buf.release();
            System.out.println("Неизвестная команда");
            ctx.writeAndFlush(STATE_ERROR);
        }
    }


    private ByteBuf uploadFile(ByteBuf buf) {

        ByteBuf bufAnswer = ByteBufAllocator.DEFAULT.directBuffer();
        System.out.println("Получение файла");

        try {
            if (currentState == StateUpload.NAME_LENGTH) {
                if (buf.readableBytes() >= 4) {
                    fileUploadNameLength = buf.readInt();
                    System.out.println("Получили длину имени файла: " + fileUploadNameLength);
                    currentState = StateUpload.NAME;
                }
            }

            if (currentState == StateUpload.NAME) {
                if (buf.readableBytes() >= fileUploadNameLength) {
                    byte[] fileNameBytes = new byte[fileUploadNameLength];
                    buf.readBytes(fileNameBytes);
                    fileUploadName = new String(fileNameBytes, "UTF-8");
                    System.out.println("Получили имя файла: " + fileUploadName);
                    currentState = StateUpload.FILE_LENGTH;
                }
            }

            if (currentState == StateUpload.FILE_LENGTH) {
                if (buf.readableBytes() >= 8) {
                    fileUploadLength = buf.readLong();
                    System.out.println("Получили длину файла: " + fileUploadLength);
                    currentState = StateUpload.FILE;
                }
            }

            if (currentState == StateUpload.FILE) {
                try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream("server/" + username + "/" + fileUploadName, true))) {
                    while (buf.readableBytes() > 0) {
                        out.write(buf.readByte());
                        receivedFileUploadLength++;
                        if (fileUploadLength == receivedFileUploadLength) {
                            System.out.println("Файл получен");
                            command = COMMAND_UNDEFINED;
                            currentState = StateUpload.NAME_LENGTH;
                            receivedFileUploadLength = 0L;
                            bufAnswer.writeByte(MainHandler.getStateOk());
                        }
                    }
                }
            }
        } catch (Exception e) {
            bufAnswer.writeByte(MainHandler.getStateError());
            System.out.println("Не удалось получить файл");
        }

        buf.release();

        return bufAnswer;
    }

    private ByteBuf downloadFile(ByteBuf buf) {
        ByteBuf bufAnswer = ByteBufAllocator.DEFAULT.directBuffer();
        System.out.println("Скачивание файла");

        try {
            int fileNameLength = buf.readInt();
            System.out.println("Длина имени файла: " + fileNameLength);
            byte[] fileNameBytes = new byte[fileNameLength];
            buf.readBytes(fileNameBytes);
            String fileName = new String(fileNameBytes, "UTF-8");
            System.out.println("Название файла: " + fileName);
            Path path = Paths.get("server", username, fileName);
            long fileLength = Files.size(path);
            System.out.println("Длина файла: " + fileLength);
            bufAnswer.writeByte(MainHandler.getStateOk());
            bufAnswer.writeLong(fileLength);
            bufAnswer.writeBytes(Files.readAllBytes(path));
            System.out.println("Файл отправлен");
        } catch (IOException e) {
            bufAnswer.writeByte(MainHandler.getStateError());
            System.out.println("Файл не найден");
        }

        buf.release();
        command = COMMAND_UNDEFINED;
        return bufAnswer;
    }

    private ByteBuf deleteFile(ByteBuf buf) {
        ByteBuf bufAnswer = ByteBufAllocator.DEFAULT.directBuffer(1);
        System.out.println("Удаление файла");

        try {
            int fileNameLength = buf.readInt();
            byte[] fileNameBytes = new byte[fileNameLength];
            buf.readBytes(fileNameBytes);
            String fileName = new String(fileNameBytes, "UTF-8");
            System.out.println("Название файла: " + fileName);
            Path path = Paths.get("server", username, fileName);
            Files.delete(path);
            bufAnswer.writeByte(STATE_OK);
            System.out.println("Файл удален");
        } catch (IOException e) {
            bufAnswer.writeByte(STATE_ERROR);
            System.out.println("Файл не найден");
        }
        buf.release();
        command = COMMAND_UNDEFINED;
        return bufAnswer;
    }

    private ByteBuf getListFiles(ByteBuf buf) {
        ByteBuf bufAnswer = ByteBufAllocator.DEFAULT.directBuffer();
        System.out.println("Формирование списка файлов");
        try {
            int filesCount = (int) Files.list(Paths.get("server", username)).count();
            bufAnswer.writeByte(STATE_OK);
            bufAnswer.writeInt(filesCount);
            List<FileInfo> filesList = Files.list(Paths.get("server", username)).map(FileInfo::new).collect(Collectors.toList());
            for (int i = 0; i < filesCount; i++) {
                bufAnswer.writeInt(filesList.get(i).getFileName().getBytes("UTF-8").length);
                bufAnswer.writeBytes(filesList.get(i).getFileName().getBytes("UTF-8"));
                bufAnswer.writeLong(filesList.get(i).getLength());
            }
        } catch (IOException e) {
            bufAnswer.writeByte(STATE_ERROR);
            System.out.println("Не удалось извлечь информацию о файлах");
        }
        buf.release();
        command = COMMAND_UNDEFINED;
        return bufAnswer;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}
