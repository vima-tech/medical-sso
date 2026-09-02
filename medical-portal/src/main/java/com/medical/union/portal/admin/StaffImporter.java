package com.medical.union.portal.admin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 人员批量导入。
 *
 * <p>用 CSV 而不是 Excel：医院常用的表格软件都能另存为 CSV，不需要在服务端引入表格解析依赖，
 * 出问题时管理员用记事本就能打开核对。
 *
 * <p>逐行导入而不是整批事务：一行填错不该让整张表白跑。每行的结果都回执给管理员，
 * 成功的保留、失败的按行号说明原因，改完再导一次即可，已成功的行会因为查重被识别出来。
 */
public class StaffImporter {

    /** 模板表头，顺序即列顺序。 */
    public static final List<String> HEADERS = List.of(
            "姓名", "工号", "登录名", "统一人员标识", "机构编码", "科室编码", "初始密码");

    private static final int MAX_ROWS = 1000;

    private final StaffRegistry staff;
    private final OrganizationDirectory organizations;

    public StaffImporter(StaffRegistry staff, OrganizationDirectory organizations) {
        this.staff = staff;
        this.organizations = organizations;
    }

    public String template() {
        return String.join(",", HEADERS) + "\n"
                + "张三,10086,zhangsan,P000123,H001,D001,Init@123456\n";
    }

    public Result importFrom(java.io.InputStream input, boolean mustChangePassword) {
        List<Row> rows = new ArrayList<>();
        int succeeded = 0;

        Set<String> validCodes = new LinkedHashSet<>();
        Map<String, String> departmentToOrganization = new LinkedHashMap<>();
        for (OrganizationOption organization : organizations.organizations()) {
            validCodes.add(organization.code());
            for (OrganizationOption.DepartmentOption department : organization.departments()) {
                validCodes.add(department.code());
                departmentToOrganization.put(department.code(), organization.code());
            }
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber == 1 && line.contains(HEADERS.get(0))) {
                    continue;   // 跳过表头
                }
                if (line.isBlank()) {
                    continue;
                }
                if (rows.size() >= MAX_ROWS) {
                    rows.add(new Row(lineNumber, "", "单次最多导入 " + MAX_ROWS + " 行，请拆分后分批导入", false));
                    break;
                }
                // Excel 另存 CSV 时可能带 BOM，去掉以免第一列名对不上
                String cleaned = line.replace("﻿", "").trim();
                String[] cells = cleaned.split(",", -1);
                if (cells.length < 7) {
                    rows.add(new Row(lineNumber, cleaned, "列数不足，应为 " + HEADERS.size() + " 列", false));
                    continue;
                }
                StaffForm form = new StaffForm();
                form.setName(cells[0].trim());
                form.setEmployeeNo(cells[1].trim());
                form.setUsername(cells[2].trim());
                form.setPersonId(cells[3].trim());
                form.setOrganizationCode(cells[4].trim());
                form.setDepartmentCode(cells[5].trim());
                form.setInitialPassword(cells[6].trim());
                form.setMustChangePassword(mustChangePassword);
                form.setEnabled(true);

                String problem = validate(form, validCodes, departmentToOrganization);
                if (problem != null) {
                    rows.add(new Row(lineNumber, form.getName(), problem, false));
                    continue;
                }
                try {
                    staff.create(form);
                    rows.add(new Row(lineNumber, form.getName(), "已导入", true));
                    succeeded++;
                } catch (IllegalArgumentException | IllegalStateException ex) {
                    rows.add(new Row(lineNumber, form.getName(), ex.getMessage(), false));
                }
            }
        } catch (IOException ex) {
            rows.add(new Row(0, "", "读取文件失败：" + ex.getMessage(), false));
        }
        return new Result(succeeded, rows.size() - succeeded, rows);
    }

    private String validate(StaffForm form, Set<String> validCodes,
                            Map<String, String> departmentToOrganization) {
        if (form.getName().isEmpty()) {
            return "姓名为空";
        }
        if (form.getEmployeeNo().isEmpty()) {
            return "工号为空";
        }
        if (!form.getUsername().matches("[a-zA-Z0-9._-]{3,64}")) {
            return "登录名不合法，应为 3 到 64 位字母、数字、点、下划线或中划线";
        }
        if (form.getPersonId().isEmpty()) {
            return "统一人员标识为空";
        }
        if (!validCodes.contains(form.getOrganizationCode())) {
            return "机构编码 " + form.getOrganizationCode() + " 不存在";
        }
        if (!validCodes.contains(form.getDepartmentCode())) {
            return "科室编码 " + form.getDepartmentCode() + " 不存在";
        }
        String parent = departmentToOrganization.get(form.getDepartmentCode());
        if (parent != null && !parent.equals(form.getOrganizationCode())) {
            return "科室 " + form.getDepartmentCode() + " 不属于机构 " + form.getOrganizationCode();
        }
        if (form.getInitialPassword() == null || form.getInitialPassword().isEmpty()) {
            return "初始密码为空";
        }
        return null;
    }

    /** 一行的导入结果。 */
    public record Row(int lineNumber, String name, String message, boolean ok) {
    }

    /** 整次导入的回执。 */
    public record Result(int succeeded, int failed, List<Row> rows) {

        public boolean hasFailures() {
            return failed > 0;
        }
    }
}
