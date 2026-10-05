package de.smartdata.porter.importer.descriptors;

import java.awt.image.BufferedImage;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * Class describing a stream
 *
 * @author ffehring
 */
public class SourceDescriptor {

    String name;
    String path;
    String origin;
    String mimetype;
    OffsetDateTime fileDate;
    LocalDateTime createDateTime;
    LocalDateTime modifyDateTime;
    byte[] fileBytes;
    BufferedImage sharedImage = null;
    int[] pixels = null;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String path) {
        this.origin = path;
    }

    public String getMimeType() {
        return mimetype;
    }

    public void setMimeType(String mimetype) {
        this.mimetype = mimetype;
    }

    public void setFileDate(OffsetDateTime dt) {
        this.fileDate = dt;
    }

    public OffsetDateTime getFileDate() {
        return this.fileDate;
    }

    public LocalDateTime getCreateDateTime() {
        return createDateTime;
    }

    public void setCreateDateTime(LocalDateTime createDateTime) {
        this.createDateTime = createDateTime;
    }

    public LocalDateTime getModifyDateTime() {
        return modifyDateTime;
    }

    public void setModifyDateTime(LocalDateTime modifyDateTime) {
        this.modifyDateTime = modifyDateTime;
    }

    public byte[] getFileBytes() {
        return fileBytes;
    }

    public void setFileBytes(byte[] fileBytes) {
        this.fileBytes = fileBytes;
    }

    public BufferedImage getSharedImage() {
        return sharedImage;
    }

    public void setSharedImage(BufferedImage sharedImage) {
        this.sharedImage = sharedImage;
    }

    public int[] getPixels() {
        return pixels;
    }

    public void setPixels(int[] pixels) {
        this.pixels = pixels;
    }
}
