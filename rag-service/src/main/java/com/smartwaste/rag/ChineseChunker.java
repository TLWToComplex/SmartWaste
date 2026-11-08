package com.smartwaste.rag;

import org.springframework.stereotype.Component;
import java.util.*;

/** 按 Markdown 标题保留章节；长段落按中文标点切分，并保留 80 字重叠。 */
@Component
public class ChineseChunker {
    public List<Knowledge.Chunk> split(String id, String revision, Knowledge.Input input) {
        String text = input.content().replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "").strip();
        List<Knowledge.Chunk> chunks = new ArrayList<>();
        String section = input.title();
        StringBuilder paragraph = new StringBuilder();
        for (String line : text.split("\n")) {
            if (line.matches("^#{1,6}\\s+.*")) {
                append(chunks, id, revision, input, section, paragraph.toString());
                paragraph.setLength(0);
                section = line.replaceFirst("^#{1,6}\\s+", "").strip();
            } else paragraph.append(line).append('\n');
        }
        append(chunks, id, revision, input, section, paragraph.toString());
        if (chunks.isEmpty()) throw new IllegalArgumentException("知识内容不能为空或只有标题");
        return List.copyOf(chunks);
    }

    private void append(List<Knowledge.Chunk> out, String id, String revision, Knowledge.Input input,
                        String section, String text) {
        text = text.strip();
        for (int start=0; start<text.length();) {
            int end = Math.min(start+600, text.length());
            if (end<text.length()) {
                int cut=end;
                while (cut>start+350 && "。！？；\n".indexOf(text.charAt(cut-1))<0) cut--;
                if (cut>start+350) end=cut;
            }
            // 不在 UTF-16 代理对中间切断。
            if (end<text.length() && Character.isLowSurrogate(text.charAt(end))) end--;
            String content=text.substring(start,end).strip();
            if (!content.isEmpty()) {
                int position=out.size();
                out.add(new Knowledge.Chunk(id+"_"+revision+"_"+position,id,revision,
                        input.title().strip(),input.category(),input.source().strip(),section,position,content));
            }
            if (end==text.length()) break;
            start=Math.max(start+1,end-80);
            if (Character.isLowSurrogate(text.charAt(start))) start++;
        }
    }
}
