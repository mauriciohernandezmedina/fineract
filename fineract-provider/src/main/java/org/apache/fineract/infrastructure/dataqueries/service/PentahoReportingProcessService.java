/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.infrastructure.dataqueries.service;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.dataqueries.data.ReportExportType;
import org.apache.fineract.infrastructure.report.annotation.ReportService;
import org.apache.fineract.infrastructure.report.service.AbstractReportingProcessService;
import org.apache.fineract.infrastructure.report.service.ReportParameterTypeResolver;
import org.apache.fineract.infrastructure.security.exception.InputValidationException;
import org.apache.fineract.infrastructure.security.service.InputValidator;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFCellStyle;
import org.apache.poi.hssf.usermodel.HSSFFont;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.dom4j.io.SAXReader;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.rowset.SqlRowSet;
import org.springframework.jdbc.support.rowset.SqlRowSetMetaData;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ReportService(type = "Pentaho")
public class PentahoReportingProcessService extends AbstractReportingProcessService {

    private static final String INSTITUTION_TITLE = "FONDO DE EMPLEADOS FAC FUNDEVI";
    private static final String DEFAULT_DATE_FORMAT = "dd MMMM yyyy";
    private static final String DEFAULT_LOCALE = "es";
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");

    private static final Set<String> SINGLE_ROW_CARD_REPORTS = Set.of("Loan Transaction Receipt", "Savings Transaction Receipt",
            "LoanApplicationReport", "KYCNaturalPerson", "KYCReport");

    private static final Set<String> DECIMAL_AMOUNT_HEADERS = Set.of("Monto", "Monto Total", "Monto Solicitado", "Capital",
            "Capital Programado", "Capital Pagado", "Interés", "Interés Programado", "Interés Pagado", "Cargos", "Mora", "Saldo Préstamo",
            "Saldo Cuenta", "Saldo Acumulado", "Total Cuota", "Total Capital Programado", "Total Interés Programado",
            "Tasa Interés Nominal (%)", "Ingreso Mensual Promedio", "Egresos Mensuales");

    private static final Set<String> INTEGER_HEADERS = Set.of("No. Recibo", "ID Transacción", "ID Asociado", "No. Cuotas", "Cuota",
            "Abonos Extra Pactados");

    private static final List<ReportExportType> AVAILABLE_EXPORT_TARGETS = List.of(new ReportExportType("HTML", "HTML"),
            new ReportExportType("PDF", "PDF"), new ReportExportType("XLS", "XLS"), new ReportExportType("CSV", "CSV"));

    private static final String EXTENDED_PARAM_TYPE_SQL = """
            SELECT sp.parameter_variable, srp.report_parameter_name, sp."parameter_FormatType" AS format_type
            FROM stretchy_report_parameter srp
            JOIN stretchy_parameter sp ON sp.id = srp.parameter_id
            JOIN stretchy_report sr ON sr.id = srp.report_id
            WHERE sr.report_name = ?
            """;

    private static final Map<String, String> REPORT_DISPLAY_TITLES = Map.of("Loan Transaction Receipt", "Recibo de Transacción de Préstamo",
            "Savings Transaction Receipt", "Recibo de Transacción de Ahorro", "Client Loan Account Schedule",
            "Estado de Transacciones de Préstamo", "Client Saving Transactions", "Extracto de Movimientos de Ahorro",
            "LoanApplicationReport", "Solicitud de Crédito", "KYCNaturalPerson", "Ficha de Conocimiento del Asociado (KYC)", "KYCReport",
            "Ficha de Conocimiento del Asociado (KYC)");

    private static final Map<String, String> BUILTIN_POSTGRES_QUERIES = buildBuiltinPostgresQueries();

    private final JdbcTemplate jdbcTemplate;
    private final PlatformSecurityContext context;
    private final InputValidator inputValidator;
    private final ReportParameterTypeResolver reportParameterTypeResolver;
    private final Path reportsDirectory;

    @Autowired
    public PentahoReportingProcessService(InputValidator inputValidator, ReportParameterTypeResolver reportParameterTypeResolver,
            JdbcTemplate jdbcTemplate, PlatformSecurityContext context) {
        this(inputValidator, reportParameterTypeResolver, jdbcTemplate, context, resolveDefaultReportsDirectory());
    }

    @Bean
    static BeanFactoryPostProcessor datatableReportingPrimaryPostProcessor() {
        return beanFactory -> {
            if (beanFactory.containsBeanDefinition("datatableReportingProcessService")) {
                beanFactory.getBeanDefinition("datatableReportingProcessService").setPrimary(true);
            }
        };
    }

    PentahoReportingProcessService(InputValidator inputValidator, ReportParameterTypeResolver reportParameterTypeResolver,
            JdbcTemplate jdbcTemplate, PlatformSecurityContext context, Path reportsDirectory) {
        super(inputValidator, reportParameterTypeResolver);
        this.inputValidator = inputValidator;
        this.reportParameterTypeResolver = reportParameterTypeResolver;
        this.jdbcTemplate = jdbcTemplate;
        this.context = context;
        this.reportsDirectory = reportsDirectory;
    }

    private static Path resolveDefaultReportsDirectory() {
        String envPath = System.getenv("FINERACT_PENTAHO_REPORTS_PATH");
        if (StringUtils.isNotBlank(envPath)) {
            return Path.of(envPath);
        }
        return Path.of(System.getProperty("user.home") + File.separator + ".mifosx" + File.separator + "pentahoReports");
    }

    @Override
    public List<ReportExportType> getAvailableExportTargets() {
        return AVAILABLE_EXPORT_TARGETS;
    }

    @Override
    public Map<String, String> getReportParams(final String reportName, final MultivaluedMap<String, String> queryParams) {
        final String dateFormat = StringUtils.defaultIfBlank(queryParams.getFirst("dateFormat"), DEFAULT_DATE_FORMAT);
        final String localeStr = StringUtils.defaultIfBlank(queryParams.getFirst("locale"), DEFAULT_LOCALE);
        final Locale resolvedLocale = Locale.forLanguageTag(localeStr);

        final Map<String, String> paramFormatTypes = new HashMap<>();
        final Map<String, String> aliasToVariable = new HashMap<>();
        loadExtendedParamMetadata(reportName, paramFormatTypes, aliasToVariable);

        final boolean hasRegisteredParams = !paramFormatTypes.isEmpty();
        final Map<String, String> reportParams = new HashMap<>();

        for (Map.Entry<String, List<String>> entry : queryParams.entrySet()) {
            if (entry.getKey().startsWith("R_") && entry.getValue() != null && !entry.getValue().isEmpty()) {
                final String paramName = entry.getKey().substring(2);
                String value = entry.getValue().get(0);

                if (hasRegisteredParams) {
                    final String formatType = paramFormatTypes.get(paramName);
                    if (formatType == null) {
                        log.warn("Report '{}' received unknown parameter '{}' with no registered type — rejected", reportName, paramName);
                        throw new InputValidationException(
                                String.format("unknown report parameter '%s' is not registered for report '%s'", paramName, reportName));
                    }
                    value = normalizeAndValidateParam(paramName, value, formatType, dateFormat, resolvedLocale);
                }

                reportParams.put("${" + paramName + "}", value);
                final String variableAlias = aliasToVariable.get(paramName);
                if (StringUtils.isNotBlank(variableAlias)) {
                    reportParams.put("${" + variableAlias + "}", value);
                }
            }
        }
        return reportParams;
    }

    @Override
    public Response processRequest(final String reportName, final MultivaluedMap<String, String> queryParams) {
        this.context.authenticatedUser();

        final String rawOutputType = StringUtils.defaultIfBlank(queryParams.getFirst("output-type"), "HTML");
        final String outputType = rawOutputType.trim().toUpperCase(Locale.ROOT);
        final Map<String, String> reportParams = getReportParams(reportName, queryParams);
        final String sqlTemplate = resolveReportSql(reportName);

        final List<Object> boundArgs = new ArrayList<>();
        final String boundSql = bindSqlParameters(sqlTemplate, reportParams, boundArgs);

        final SqlRowSet rowSet = jdbcTemplate.queryForRowSet(boundSql, boundArgs.toArray());
        final ReportTableData tableData = extractTableData(rowSet);
        final String sanitizedFileName = sanitizeFileName(reportName);

        return switch (outputType) {
            case "PDF" -> Response.ok(renderPdf(reportName, tableData)).type("application/pdf")
                    .header("Content-Disposition", "inline; filename=\"" + sanitizedFileName + ".pdf\"").build();
            case "HTML" -> Response.ok(renderHtml(reportName, tableData)).type("text/html; charset=UTF-8").build();
            case "XLS" -> Response.ok(renderXls(reportName, tableData)).type("application/vnd.ms-excel")
                    .header("Content-Disposition", "attachment; filename=\"" + sanitizedFileName + ".xls\"").build();
            case "CSV" -> Response.ok(renderCsv(tableData)).type("text/csv; charset=UTF-8")
                    .header("Content-Disposition", "attachment; filename=\"" + sanitizedFileName + ".csv\"").build();
            default -> throw new PlatformDataIntegrityException("error.msg.report.export.mode.unavailable",
                    "Unsupported output-type: " + outputType);
        };
    }

    private void loadExtendedParamMetadata(final String reportName, final Map<String, String> paramFormatTypes,
            final Map<String, String> aliasToVariable) {
        final SqlRowSet rs = jdbcTemplate.queryForRowSet(EXTENDED_PARAM_TYPE_SQL, reportName);
        if (rs != null) {
            while (rs.next()) {
                final String variable = rs.getString("parameter_variable");
                final String reportParamName = rs.getString("report_parameter_name");
                final String formatType = rs.getString("format_type");
                if (StringUtils.isNotBlank(variable)) {
                    paramFormatTypes.put(variable, formatType);
                }
                if (StringUtils.isNotBlank(reportParamName)) {
                    paramFormatTypes.put(reportParamName, formatType);
                    if (StringUtils.isNotBlank(variable) && !reportParamName.equals(variable)) {
                        aliasToVariable.put(reportParamName, variable);
                    }
                }
            }
        }
        if (paramFormatTypes.isEmpty()) {
            final Map<String, String> fallbackTypes = reportParameterTypeResolver.loadParamFormatTypes(reportName);
            if (fallbackTypes != null) {
                paramFormatTypes.putAll(fallbackTypes);
            }
        }
    }

    private String normalizeAndValidateParam(final String paramName, final String rawValue, final String formatType,
            final String dateFormat, final Locale primaryLocale) {
        String value = rawValue;
        if ("date".equalsIgnoreCase(formatType)) {
            if (!ISO_DATE_PATTERN.matcher(value).matches()) {
                value = parseAndFormatIsoDate(paramName, value, dateFormat, primaryLocale);
            }
            inputValidator.validate("date", value);
        } else if ("number".equalsIgnoreCase(formatType)) {
            inputValidator.validate("number", value);
        }
        return value;
    }

    private String parseAndFormatIsoDate(final String paramName, final String rawValue, final String dateFormat,
            final Locale primaryLocale) {
        final List<Locale> candidateLocales = List.of(primaryLocale, Locale.forLanguageTag("es"), Locale.ENGLISH);
        for (Locale locale : candidateLocales) {
            try {
                final DateTimeFormatter formatter = DateTimeFormatter.ofPattern(dateFormat, locale);
                final LocalDate parsed = LocalDate.parse(rawValue, formatter);
                return parsed.format(DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException | IllegalArgumentException ignored) {
                // Try next locale
            }
        }
        throw new InputValidationException(
                String.format("Invalid date value '%s' for parameter '%s' with format '%s'", rawValue, paramName, dateFormat));
    }

    private String resolveReportSql(final String reportName) {
        final Path prptPath = reportsDirectory.resolve(reportName + ".prpt");
        if (Files.isRegularFile(prptPath)) {
            final String prptSql = extractSqlFromPrpt(prptPath);
            if (StringUtils.isNotBlank(prptSql)) {
                return prptSql;
            }
        }
        final String builtinSql = BUILTIN_POSTGRES_QUERIES.get(reportName);
        if (StringUtils.isNotBlank(builtinSql)) {
            return builtinSql;
        }
        throw new PlatformDataIntegrityException("error.msg.report.name.not.found", "Report not found: " + reportName);
    }

    private String extractSqlFromPrpt(final Path prptPath) {
        try (ZipFile zipFile = new ZipFile(prptPath.toFile(), StandardCharsets.UTF_8)) {
            final ZipEntry entry = zipFile.getEntry("datasources/sql-ds.xml");
            if (entry == null) {
                return null;
            }
            try (InputStream is = zipFile.getInputStream(entry)) {
                final SAXReader reader = new SAXReader();
                reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
                reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                final org.dom4j.Document doc = reader.read(is);
                final List<org.dom4j.Element> queryElements = new ArrayList<>();
                collectQueryElements(doc.getRootElement(), queryElements);
                String firstQuery = null;
                for (org.dom4j.Element element : queryElements) {
                    final String text = StringUtils.trimToNull(element.getText());
                    if (text == null) {
                        continue;
                    }
                    if (firstQuery == null) {
                        firstQuery = text;
                    }
                    final String nameAttr = element.attributeValue("name");
                    if ("Main".equalsIgnoreCase(nameAttr)) {
                        return text;
                    }
                }
                return firstQuery;
            }
        } catch (Exception ex) {
            log.warn("Could not read SQL from .prpt archive '{}': {}", prptPath, ex.getMessage());
            return null;
        }
    }

    private void collectQueryElements(final org.dom4j.Element element, final List<org.dom4j.Element> result) {
        if (element == null) {
            return;
        }
        if ("query".equalsIgnoreCase(element.getName())) {
            result.add(element);
        }
        for (org.dom4j.Element child : element.elements()) {
            collectQueryElements(child, result);
        }
    }

    private String bindSqlParameters(final String sqlTemplate, final Map<String, String> reportParams, final List<Object> boundArgs) {
        final Matcher matcher = PLACEHOLDER_PATTERN.matcher(sqlTemplate);
        final StringBuilder sqlBuilder = new StringBuilder();
        while (matcher.find()) {
            final String paramName = matcher.group(1);
            final String value = reportParams.get("${" + paramName + "}");
            if (value == null) {
                throw new PlatformDataIntegrityException("error.msg.report.parameter.missing",
                        "Missing required report parameter: " + paramName);
            }
            boundArgs.add(value);
            matcher.appendReplacement(sqlBuilder, "?");
        }
        matcher.appendTail(sqlBuilder);
        return sqlBuilder.toString();
    }

    private ReportTableData extractTableData(final SqlRowSet rowSet) {
        if (rowSet == null) {
            return new ReportTableData(Collections.emptyList(), Collections.emptyList());
        }
        final SqlRowSetMetaData metaData = rowSet.getMetaData();
        final int columnCount = metaData.getColumnCount();
        final List<String> headers = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            final String label = metaData.getColumnLabel(i);
            headers.add(StringUtils.isNotBlank(label) ? label : metaData.getColumnName(i));
        }

        final List<List<String>> rows = new ArrayList<>();
        while (rowSet.next()) {
            final List<String> row = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                final Object val = rowSet.getObject(i);
                row.add(formatCellValue(headers.get(i - 1), val));
            }
            rows.add(row);
        }
        return new ReportTableData(headers, rows);
    }

    private String formatCellValue(final String columnHeader, final Object val) {
        if (val == null) {
            return "";
        }
        if (INTEGER_HEADERS.contains(columnHeader)) {
            return String.valueOf(val);
        }
        if (val instanceof BigDecimal || val instanceof Double || val instanceof Float || DECIMAL_AMOUNT_HEADERS.contains(columnHeader)) {
            try {
                final BigDecimal decimal = val instanceof BigDecimal bd ? bd : new BigDecimal(String.valueOf(val).trim());
                final DecimalFormat formatter = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
                return formatter.format(decimal.setScale(2, RoundingMode.HALF_UP));
            } catch (NumberFormatException ignored) {
                return String.valueOf(val);
            }
        }
        return String.valueOf(val);
    }

    private byte[] renderPdf(final String reportName, final ReportTableData tableData) {
        final boolean singleRowCard = tableData.rows().size() == 1 && SINGLE_ROW_CARD_REPORTS.contains(reportName);
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final Document document = new Document(
                singleRowCard || tableData.headers().size() <= 6 ? PageSize.LETTER : PageSize.LETTER.rotate(), 36, 36, 36, 36);
        try {
            PdfWriter.getInstance(document, baos);
            document.open();

            final Font brandFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, new Color(21, 67, 96));
            final Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Color.DARK_GRAY);
            final Font metaFont = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.GRAY);
            final Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.WHITE);
            final Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.BLACK);
            final Font labelFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, new Color(21, 67, 96));

            final Paragraph brand = new Paragraph(INSTITUTION_TITLE, brandFont);
            brand.setAlignment(Element.ALIGN_CENTER);
            document.add(brand);

            final Paragraph title = new Paragraph(resolveDisplayTitle(reportName), titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingBefore(4f);
            document.add(title);

            final Paragraph timestamp = new Paragraph(
                    "Generado: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), metaFont);
            timestamp.setAlignment(Element.ALIGN_CENTER);
            timestamp.setSpacingAfter(12f);
            document.add(timestamp);

            if (tableData.headers().isEmpty()) {
                document.add(new Paragraph("Sin datos disponibles para los parámetros seleccionados.", cellFont));
            } else if (singleRowCard) {
                final PdfPTable cardTable = new PdfPTable(2);
                cardTable.setWidthPercentage(100);
                cardTable.setWidths(new float[] { 35f, 65f });
                final List<String> row = tableData.rows().get(0);
                for (int i = 0; i < tableData.headers().size(); i++) {
                    final PdfPCell keyCell = new PdfPCell(new Phrase(tableData.headers().get(i), labelFont));
                    keyCell.setBackgroundColor(new Color(235, 245, 251));
                    keyCell.setPadding(6f);
                    cardTable.addCell(keyCell);

                    final PdfPCell valCell = new PdfPCell(new Phrase(i < row.size() ? row.get(i) : "", cellFont));
                    valCell.setPadding(6f);
                    cardTable.addCell(valCell);
                }
                document.add(cardTable);
            } else {
                final PdfPTable pdfTable = new PdfPTable(tableData.headers().size());
                pdfTable.setWidthPercentage(100);
                pdfTable.setHeaderRows(1);

                for (String header : tableData.headers()) {
                    final PdfPCell cell = new PdfPCell(new Phrase(header, headerFont));
                    cell.setBackgroundColor(new Color(21, 67, 96));
                    cell.setPadding(5f);
                    pdfTable.addCell(cell);
                }

                boolean alternate = false;
                for (List<String> row : tableData.rows()) {
                    for (String val : row) {
                        final PdfPCell cell = new PdfPCell(new Phrase(val, cellFont));
                        if (alternate) {
                            cell.setBackgroundColor(new Color(248, 249, 249));
                        }
                        cell.setPadding(4f);
                        pdfTable.addCell(cell);
                    }
                    alternate = !alternate;
                }
                document.add(pdfTable);
            }

            document.close();
            return baos.toByteArray();
        } catch (org.openpdf.text.DocumentException ex) {
            throw new PlatformDataIntegrityException("error.msg.report.pdf.generation", "Failed to generate PDF report", ex);
        }
    }

    private String renderHtml(final String reportName, final ReportTableData tableData) {
        final boolean singleRowCard = tableData.rows().size() == 1 && SINGLE_ROW_CARD_REPORTS.contains(reportName);
        final String displayTitle = resolveDisplayTitle(reportName);
        final StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"es\"><head><meta charset=\"UTF-8\">").append("<title>").append(escapeHtml(displayTitle))
                .append("</title>").append("<style>")
                .append("body{font-family:Arial,Helvetica,sans-serif;margin:24px;color:#212529;background:#fff;}")
                .append(".header{text-align:center;border-bottom:2px solid #154360;padding-bottom:10px;margin-bottom:18px;}")
                .append(".brand{font-size:18px;font-weight:bold;color:#154360;margin:0;}")
                .append(".title{font-size:15px;font-weight:bold;margin:6px 0 2px;}").append(".meta{font-size:11px;color:#6c757d;margin:0;}")
                .append("table{width:100%;border-collapse:collapse;font-size:12px;}")
                .append("th,td{border:1px solid #dee2e6;padding:7px 9px;text-align:left;}").append("th{background:#154360;color:#fff;}")
                .append("tr:nth-child(even) td{background:#f8f9fa;}").append(".card th{width:35%;background:#ebf5fb;color:#154360;}")
                .append("</style></head><body>").append("<div class=\"header\">").append("<p class=\"brand\">").append(INSTITUTION_TITLE)
                .append("</p>").append("<p class=\"title\">").append(escapeHtml(displayTitle)).append("</p>")
                .append("<p class=\"meta\">Generado: ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("</p>").append("</div>");

        if (tableData.headers().isEmpty()) {
            html.append("<p>Sin datos disponibles para los parámetros seleccionados.</p>");
        } else if (singleRowCard) {
            html.append("<table class=\"card\">");
            final List<String> row = tableData.rows().get(0);
            for (int i = 0; i < tableData.headers().size(); i++) {
                html.append("<tr><th>").append(escapeHtml(tableData.headers().get(i))).append("</th><td>")
                        .append(escapeHtml(i < row.size() ? row.get(i) : "")).append("</td></tr>");
            }
            html.append("</table>");
        } else {
            html.append("<table><thead><tr>");
            for (String header : tableData.headers()) {
                html.append("<th>").append(escapeHtml(header)).append("</th>");
            }
            html.append("</tr></thead><tbody>");
            for (List<String> row : tableData.rows()) {
                html.append("<tr>");
                for (String val : row) {
                    html.append("<td>").append(escapeHtml(val)).append("</td>");
                }
                html.append("</tr>");
            }
            html.append("</tbody></table>");
        }
        html.append("</body></html>");
        return html.toString();
    }

    private byte[] renderXls(final String reportName, final ReportTableData tableData) {
        try (HSSFWorkbook workbook = new HSSFWorkbook(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            final HSSFSheet sheet = workbook.createSheet(sanitizeSheetName(reportName));

            final HSSFFont boldFont = workbook.createFont();
            boldFont.setBold(true);
            final HSSFCellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(boldFont);

            final HSSFRow brandRow = sheet.createRow(0);
            final HSSFCell brandCell = brandRow.createCell(0);
            brandCell.setCellValue(INSTITUTION_TITLE + " - " + resolveDisplayTitle(reportName));
            brandCell.setCellStyle(headerStyle);

            final HSSFRow colHeaderRow = sheet.createRow(2);
            for (int i = 0; i < tableData.headers().size(); i++) {
                final HSSFCell cell = colHeaderRow.createCell(i);
                cell.setCellValue(tableData.headers().get(i));
                cell.setCellStyle(headerStyle);
            }

            int rowIdx = 3;
            for (List<String> row : tableData.rows()) {
                final HSSFRow dataRow = sheet.createRow(rowIdx++);
                for (int c = 0; c < row.size(); c++) {
                    dataRow.createCell(c).setCellValue(row.get(c));
                }
            }

            workbook.write(baos);
            return baos.toByteArray();
        } catch (IOException ex) {
            throw new PlatformDataIntegrityException("error.msg.report.xls.generation", "Failed to generate XLS report", ex);
        }
    }

    private String renderCsv(final ReportTableData tableData) {
        final StringWriter writer = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
            if (!tableData.headers().isEmpty()) {
                printer.printRecord(tableData.headers());
            }
            for (List<String> row : tableData.rows()) {
                printer.printRecord(row);
            }
            printer.flush();
            return writer.toString();
        } catch (IOException ex) {
            throw new PlatformDataIntegrityException("error.msg.report.csv.generation", "Failed to generate CSV report", ex);
        }
    }

    private String resolveDisplayTitle(final String reportName) {
        return REPORT_DISPLAY_TITLES.getOrDefault(reportName, reportName);
    }

    private String sanitizeFileName(final String reportName) {
        return reportName.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private String sanitizeSheetName(final String reportName) {
        final String cleaned = reportName.replaceAll("[\\\\/?*\\[\\]:]", "_");
        return cleaned.length() > 31 ? cleaned.substring(0, 31) : cleaned;
    }

    private String escapeHtml(final String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static Map<String, String> buildBuiltinPostgresQueries() {
        final Map<String, String> queries = new LinkedHashMap<>();

        queries.put("Loan Transaction Receipt", """
                SELECT lt.id AS "No. Recibo",
                       lt.transaction_date AS "Fecha Transacción",
                       COALESCE(c.display_name, '') AS "Asociado",
                       COALESCE(c.external_id, '') AS "Identificación",
                       COALESCE(o.name, '') AS "Oficina",
                       COALESCE(l.account_no, '') AS "No. Préstamo",
                       COALESCE(lp.name, '') AS "Línea de Crédito",
                       COALESCE(rev.enum_value, CAST(lt.transaction_type_enum AS TEXT)) AS "Tipo Transacción",
                       COALESCE(pt.value, 'N/A') AS "Medio de Pago",
                       COALESCE(lt.amount, 0) AS "Monto Total",
                       COALESCE(lt.principal_portion_derived, 0) AS "Capital",
                       COALESCE(lt.interest_portion_derived, 0) AS "Interés",
                       COALESCE(lt.fee_charges_portion_derived, 0) AS "Cargos",
                       COALESCE(lt.penalty_charges_portion_derived, 0) AS "Mora",
                       COALESCE(lt.outstanding_loan_balance_derived, 0) AS "Saldo Préstamo"
                FROM m_loan_transaction lt
                JOIN m_loan l ON l.id = lt.loan_id
                LEFT JOIN m_client c ON c.id = l.client_id
                LEFT JOIN m_office o ON o.id = c.office_id
                LEFT JOIN m_product_loan lp ON lp.id = l.product_id
                LEFT JOIN m_payment_detail pd ON pd.id = lt.payment_detail_id
                LEFT JOIN m_payment_type pt ON pt.id = pd.payment_type_id
                LEFT JOIN r_enum_value rev ON rev.enum_name = 'transaction_type_enum' AND rev.enum_id = lt.transaction_type_enum
                WHERE CAST(lt.id AS TEXT) = ${transactionId}
                """);

        queries.put("Savings Transaction Receipt", """
                SELECT st.id AS "No. Recibo",
                       st.transaction_date AS "Fecha Transacción",
                       COALESCE(c.display_name, '') AS "Asociado",
                       COALESCE(c.external_id, '') AS "Identificación",
                       COALESCE(o.name, '') AS "Oficina",
                       COALESCE(sa.account_no, '') AS "No. Cuenta Ahorro",
                       COALESCE(sp.name, '') AS "Producto Ahorro",
                       CASE WHEN st.transaction_type_enum = 1 THEN 'Depósito'
                            WHEN st.transaction_type_enum = 2 THEN 'Retiro'
                            WHEN st.transaction_type_enum = 3 THEN 'Interés'
                            ELSE CAST(st.transaction_type_enum AS TEXT) END AS "Tipo Transacción",
                       COALESCE(pt.value, 'N/A') AS "Medio de Pago",
                       COALESCE(st.amount, 0) AS "Monto",
                       COALESCE(st.running_balance_derived, 0) AS "Saldo Cuenta"
                FROM m_savings_account_transaction st
                JOIN m_savings_account sa ON sa.id = st.savings_account_id
                LEFT JOIN m_client c ON c.id = sa.client_id
                LEFT JOIN m_office o ON o.id = c.office_id
                LEFT JOIN m_savings_product sp ON sp.id = sa.product_id
                LEFT JOIN m_payment_detail pd ON pd.id = st.payment_detail_id
                LEFT JOIN m_payment_type pt ON pt.id = pd.payment_type_id
                WHERE CAST(st.id AS TEXT) = ${transactionId}
                """);

        queries.put("Client Loan Account Schedule", """
                SELECT COALESCE(l.account_no, '') AS "No. Préstamo",
                       COALESCE(c.display_name, '') AS "Asociado",
                       lt.id AS "ID Transacción",
                       lt.transaction_date AS "Fecha",
                       CASE lt.transaction_type_enum
                            WHEN 1 THEN 'Desembolso'
                            WHEN 2 THEN 'Pago de Cuota'
                            WHEN 4 THEN 'Condonación de Interés'
                            WHEN 5 THEN 'Pago en Desembolso'
                            WHEN 6 THEN 'Castigo de Cartera'
                            WHEN 8 THEN 'Recuperación'
                            WHEN 9 THEN 'Condonación de Cargos'
                            WHEN 10 THEN 'Causación de Interés'
                            ELSE COALESCE(rev.enum_value, CAST(lt.transaction_type_enum AS TEXT)) END AS "Tipo Transacción",
                       COALESCE(lt.amount, 0) AS "Monto Total",
                       COALESCE(lt.principal_portion_derived, 0) AS "Capital",
                       COALESCE(lt.interest_portion_derived, 0) AS "Interés",
                       COALESCE(lt.fee_charges_portion_derived, 0) AS "Cargos",
                       COALESCE(lt.penalty_charges_portion_derived, 0) AS "Mora",
                       COALESCE(lt.outstanding_loan_balance_derived, 0) AS "Saldo Préstamo"
                FROM m_loan_transaction lt
                JOIN m_loan l ON l.id = lt.loan_id
                LEFT JOIN m_client c ON c.id = l.client_id
                LEFT JOIN r_enum_value rev ON rev.enum_name = 'transaction_type_enum' AND rev.enum_id = lt.transaction_type_enum
                WHERE (l.account_no = ${selectLoan} OR CAST(l.id AS TEXT) = ${selectLoan})
                  AND lt.is_reversed = false
                  AND lt.transaction_date >= CAST(${startDate} AS DATE)
                  AND lt.transaction_date <= CAST(${endDate} AS DATE)
                ORDER BY lt.transaction_date ASC, lt.id ASC
                """);

        queries.put("Client Saving Transactions", """
                SELECT COALESCE(sa.account_no, '') AS "No. Cuenta",
                       COALESCE(c.display_name, '') AS "Asociado",
                       st.id AS "ID Transacción",
                       st.transaction_date AS "Fecha",
                       CASE WHEN st.transaction_type_enum = 1 THEN 'Depósito'
                            WHEN st.transaction_type_enum = 2 THEN 'Retiro'
                            WHEN st.transaction_type_enum = 3 THEN 'Interés'
                            ELSE CAST(st.transaction_type_enum AS TEXT) END AS "Tipo Movimiento",
                       COALESCE(st.amount, 0) AS "Monto",
                       COALESCE(st.running_balance_derived, 0) AS "Saldo Acumulado"
                FROM m_savings_account_transaction st
                JOIN m_savings_account sa ON sa.id = st.savings_account_id
                LEFT JOIN m_client c ON c.id = sa.client_id
                WHERE (sa.account_no = ${savingsAccountId} OR CAST(sa.id AS TEXT) = ${savingsAccountId})
                  AND st.is_reversed = false
                  AND st.transaction_date >= CAST(${startDate} AS DATE)
                  AND st.transaction_date <= CAST(${endDate} AS DATE)
                ORDER BY st.transaction_date ASC, st.id ASC
                """);

        queries.put("LoanApplicationReport", """
                SELECT COALESCE(l.account_no, CAST(l.id AS TEXT)) AS "No. Solicitud / Cuenta",
                       COALESCE(c.display_name, '') AS "Asociado",
                       COALESCE(c.external_id, '') AS "Identificación",
                       COALESCE(o.name, '') AS "Oficina",
                       COALESCE(lp.name, '') AS "Línea de Crédito",
                       l.submittedon_date AS "Fecha Solicitud",
                       COALESCE(l.principal_amount_proposed, l.principal_amount, 0) AS "Monto Solicitado",
                       COALESCE(l.nominal_interest_rate_per_period, 0) AS "Tasa Interés Nominal (%)",
                       COALESCE(l.number_of_repayments, 0) AS "No. Cuotas",
                       (SELECT COUNT(*) FROM m_loan_term_variations ltv WHERE ltv.loan_id = l.id) AS "Abonos Extra Pactados",
                       COALESCE(l.total_expected_repayment_derived, 0) AS "Total Capital Programado",
                       COALESCE(l.total_expected_costofloan_derived, 0) AS "Total Interés Programado"
                FROM m_loan l
                LEFT JOIN m_client c ON c.id = l.client_id
                LEFT JOIN m_office o ON o.id = c.office_id
                LEFT JOIN m_product_loan lp ON lp.id = l.product_id
                WHERE CAST(l.id AS TEXT) = ${loanId} OR l.account_no = ${loanId}
                """);

        final String kycSql = """
                SELECT c.id AS "ID Asociado",
                       COALESCE(c.account_no, '') AS "No. Cuenta Asociado",
                       COALESCE(c.display_name, '') AS "Nombre Completo",
                       COALESCE(c.external_id, '') AS "Identificación Externa",
                       COALESCE(c.mobile_no, '') AS "Móvil",
                       COALESCE(c.email_address, '') AS "Correo Electrónico",
                       COALESCE(o.name, '') AS "Oficina",
                       c.activation_date AS "Fecha Vinculación",
                       CASE WHEN c.status_enum = 300 THEN 'Activo'
                            WHEN c.status_enum = 100 THEN 'Pendiente'
                            WHEN c.status_enum = 600 THEN 'Cerrado'
                            ELSE CAST(c.status_enum AS TEXT) END AS "Estado",
                       COALESCE(cap.marital_status, '') AS "Estado Civil",
                       COALESCE(cap.nationality, '') AS "Nacionalidad",
                       COALESCE(csi.occupation, '') AS "Ocupación",
                       COALESCE(csi.company_where_you_work, '') AS "Empresa / Lugar de Trabajo",
                       COALESCE(csi.average_monthly_income, 0) AS "Ingreso Mensual Promedio",
                       COALESCE(csi.average_expenses_monthly, 0) AS "Egresos Mensuales"
                FROM m_client c
                LEFT JOIN m_office o ON o.id = c.office_id
                LEFT JOIN client_additional_profile cap ON cap.client_id = c.id
                LEFT JOIN client_socioeconomic_information csi ON csi.client_id = c.id
                WHERE CAST(c.id AS TEXT) = ${clientId} OR c.account_no = ${clientId}
                """;
        queries.put("KYCNaturalPerson", kycSql);
        queries.put("KYCReport", kycSql);

        return Collections.unmodifiableMap(queries);
    }

    private record ReportTableData(List<String> headers, List<List<String>> rows) {
    }
}
