package com.example.shizhan.rubbish.entity;


import lombok.Data;
import java.util.List;

@Data
public class ClassifyData {
    private String name;
    private String sort;
    private String rate;
    private Integer sortId;
    private Double confidence;
    private Boolean uncertain;
    private String message;
    private String modelVersion;
    private List<Candidate> candidates;

    @Data
    public static class Candidate {
        private String name;
        private String sort;
        private Double confidence;
    }
}
