package com.lakeon.model.enums;

public enum ComputeSize {
    CU_1("1cu", "1", "2Gi"),
    CU_2("2cu", "2", "4Gi"),
    CU_4("4cu", "4", "8Gi"),
    CU_8("8cu", "8", "16Gi");

    private final String label;
    private final String cpu;
    private final String memory;

    ComputeSize(String label, String cpu, String memory) {
        this.label = label;
        this.cpu = cpu;
        this.memory = memory;
    }

    public String getLabel() {
        return label;
    }

    public String getCpu() {
        return cpu;
    }

    public String getMemory() {
        return memory;
    }

    public static ComputeSize fromLabel(String label) {
        for (ComputeSize size : values()) {
            if (size.label.equals(label)) {
                return size;
            }
        }
        return CU_1;
    }
}
