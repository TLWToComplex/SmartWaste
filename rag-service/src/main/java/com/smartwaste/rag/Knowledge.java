package com.smartwaste.rag;

import jakarta.validation.constraints.*;
import java.util.List;

public final class Knowledge {
    private Knowledge() { }
    public record Input(@NotBlank @Size(max=120) String title,
                        @NotBlank @Pattern(regexp="classification|disposal|device|platform") String category,
                        @NotBlank @Size(max=300) String source,
                        @NotBlank @Size(max=100000) String content) { }
    public record Document(String id, String revision, String title, String category, String source,
                           String content, String updatedAt, List<Chunk> chunks) { }
    public record Chunk(String id, String documentId, String revision, String title, String category,
                        String source, String section, int position, String content) { }
    public record Summary(String id, String title, String category, String source, String updatedAt,
                          int chunks) { }
}
