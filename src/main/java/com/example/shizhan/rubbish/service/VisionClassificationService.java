package com.example.shizhan.rubbish.service;

import com.example.shizhan.rubbish.common.BusinessException;
import com.example.shizhan.rubbish.entity.ClassifyData;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.*;

/** 业务后端保留用户鉴权，通过内部密钥调用 Python ResNet18 推理服务。 */
@Service
public class VisionClassificationService {
    private static final String[] SORTS = {"", "可回收垃圾", "有害垃圾", "湿垃圾", "干垃圾"};
    private final RestTemplate http;
    private final String url, token;
    private final int maxBytes;

    public VisionClassificationService(@Value("${smartwaste.vision.url:http://127.0.0.1:8082}") String url,
            @Value("${smartwaste.vision.internal-token:}") String token,
            @Value("${smartwaste.vision.max-bytes:5242880}") int maxBytes,
            @Value("${smartwaste.vision.read-timeout-millis:20000}") int timeout) {
        this.url = url.replaceAll("/+$", "");
        this.token = token;
        if (maxBytes < 1024 || maxBytes > 6 * 1024 * 1024 || timeout < 1000 || timeout > 120000)
            throw new IllegalArgumentException("图片识别上传限制或超时设置无效");
        this.maxBytes = maxBytes;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(timeout);
        this.http = new RestTemplate(factory);
    }

    public ClassifyData classify(MultipartFile file) {
        if (token.length() < 32) throw new BusinessException("拍照识别尚未配置，请联系管理员配置图片识别服务");
        if (file == null || file.isEmpty()) throw new BusinessException("请选择需要识别的图片");
        if (file.getSize() > maxBytes) throw new BusinessException("图片过大，请压缩后上传（上限 " + maxBytes / 1024 + "KB）");
        try {
            byte[] bytes = file.getBytes();
            // 基于文件签名构造 MIME 和固定文件名，不信任浏览器传来的扩展名。
            String type = imageType(bytes);
            if (type == null) throw new BusinessException("仅支持有效的 JPEG、PNG、WebP 图片");
            final String filename = "image." + ("image/jpeg".equals(type) ? "jpg" : "image/png".equals(type) ? "png" : "webp");
            ByteArrayResource image = new ByteArrayResource(bytes) {
                @Override public String getFilename() { return filename; }
            };
            HttpHeaders partHeaders = new HttpHeaders();
            partHeaders.setContentType(MediaType.parseMediaType(type));
            MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
            parts.add("file", new HttpEntity<>(image, partHeaders));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.set("X-Vision-Token", token);
            JsonNode result = http.exchange(url + "/internal/vision/predict", HttpMethod.POST,
                    new HttpEntity<>(parts, headers), JsonNode.class).getBody();
            return mapResult(result);
        } catch (HttpStatusCodeException ex) {
            int status = ex.getRawStatusCode();
            if (status == 413) throw new BusinessException("图片超过识别服务的大小限制，请压缩后上传");
            if (status == 400 || status == 415 || status == 422) throw new BusinessException("图片格式无效、分辨率过高或包含多帧，请重新选择静态图片");
            if (status == 429) throw new BusinessException("图片识别服务繁忙，请稍后重试");
            if (status == 401) throw new BusinessException("图片识别服务认证失败，请管理员核对内部密钥");
            throw new BusinessException("图片识别模型尚未就绪或推理失败，请管理员检查训练权重和服务状态");
        } catch (ResourceAccessException ex) {
            throw new BusinessException("无法连接图片识别服务或识别超时，请稍后重试");
        } catch (RestClientException ex) {
            throw new BusinessException("图片识别服务响应异常，请稍后重试");
        } catch (IOException ex) {
            throw new BusinessException("图片读取失败，请重新选择图片");
        }
    }

    private ClassifyData mapResult(JsonNode result) {
        if (result == null || !result.isObject() || !result.path("uncertain").isBoolean())
            throw new BusinessException("图片识别服务返回的数据不完整");
        ClassifyData value = new ClassifyData();
        value.setName(text(result, "name", 120));
        int sortId = sortId(result);
        value.setSortId(sortId);
        value.setSort(SORTS[sortId]);
        value.setConfidence(confidence(result));
        value.setRate(String.format(Locale.ROOT, "%.2f%%", value.getConfidence() * 100));
        value.setUncertain(result.path("uncertain").asBoolean());
        value.setMessage(text(result, "message", 500));
        value.setModelVersion(text(result, "modelVersion", 150));
        List<ClassifyData.Candidate> candidates = new ArrayList<>();
        JsonNode rows = result.path("candidates");
        if (!rows.isArray() || rows.size() < 1 || rows.size() > 3)
            throw new BusinessException("图片识别候选结果无效");
        for (JsonNode row : rows) {
            ClassifyData.Candidate candidate = new ClassifyData.Candidate();
            candidate.setName(text(row, "name", 120));
            candidate.setSort(SORTS[sortId(row)]);
            candidate.setConfidence(confidence(row));
            candidates.add(candidate);
        }
        value.setCandidates(candidates);
        return value;
    }

    private String text(JsonNode node, String key, int max) {
        JsonNode field = node.path(key);
        if (!field.isTextual() || field.asText().trim().isEmpty() || field.asText().length() > max)
            throw new BusinessException("图片识别服务返回的文字字段无效");
        return field.asText();
    }
    private int sortId(JsonNode node) {
        int id = node.path("sortId").asInt(-1);
        if (!node.path("sortId").isIntegralNumber() || id < 1 || id > 4)
            throw new BusinessException("图片识别服务返回的垃圾类别无效");
        return id;
    }
    private double confidence(JsonNode node) {
        double score = node.path("confidence").asDouble(Double.NaN);
        if (!node.path("confidence").isNumber() || !Double.isFinite(score) || score < 0 || score > 1)
            throw new BusinessException("图片识别服务返回的置信度无效");
        return score;
    }
    private String imageType(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255) return "image/jpeg";
        if (bytes.length >= 8 && Arrays.equals(Arrays.copyOf(bytes, 8), new byte[]{(byte)137,80,78,71,13,10,26,10})) return "image/png";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "image/webp";
        return null;
    }
}
