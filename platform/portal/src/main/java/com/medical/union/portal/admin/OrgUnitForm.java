package com.medical.union.portal.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 机构或科室的新增/编辑表单。管理员只填名称和编码，不接触底层的分组结构。
 */
public class OrgUnitForm {

    /** 新增时为空。 */
    private String id;

    /** 为空表示机构；有值表示在该机构下新增科室。 */
    private String parentId;

    @NotBlank(message = "请填写名称")
    @Size(max = 64, message = "名称不超过 64 个字符")
    private String name;

    @NotBlank(message = "请填写编码")
    @Pattern(regexp = "[A-Za-z0-9_-]{1,32}", message = "编码用字母、数字、下划线或中划线，不超过 32 位")
    private String code;

    public boolean isNew() {
        return id == null || id.isBlank();
    }

    public boolean isDepartment() {
        return parentId != null && !parentId.isBlank();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
