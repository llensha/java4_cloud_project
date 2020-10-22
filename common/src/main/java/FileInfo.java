import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileInfo {

    private String fileName;
    private long length;

    public FileInfo(String filename, long length) {
        this.fileName = filename;
        this.length = length;
    }

    public FileInfo(Path path) {
        try {
            this.fileName = path.getFileName().toString();

            if (Files.isDirectory(path)) {
                this.length = -1L;
            } else {
                this.length = Files.size(path);
            }
        } catch (IOException e) {
            System.out.println("Невозможно извлечь информацию о файлах");
        }
    }

    public String getFileName() {
        return fileName;
    }

    public long getLength() {
        return length;
    }

    public boolean isDirectory() {
        return length == -1L;
    }

    public boolean isUpElement() {
        return length == -2L;
    }

    public void info() {
        System.out.println(fileName + " (" + length + " bytes)");
    }
}
