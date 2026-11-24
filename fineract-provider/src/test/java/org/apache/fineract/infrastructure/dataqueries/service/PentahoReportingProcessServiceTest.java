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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.fineract.infrastructure.dataqueries.data.ReportExportType;
import org.apache.fineract.infrastructure.report.service.ReportParameterTypeResolver;
import org.apache.fineract.infrastructure.security.service.InputValidator;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.glassfish.jersey.internal.util.collection.MultivaluedStringMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.rowset.SqlRowSet;
import org.springframework.jdbc.support.rowset.SqlRowSetMetaData;

class PentahoReportingProcessServiceTest {

    @TempDir
    Path tempDir;

    private InputValidator inputValidator;
    private ReportParameterTypeResolver reportParameterTypeResolver;
    private JdbcTemplate jdbcTemplate;
    private PlatformSecurityContext context;
    private PentahoReportingProcessService service;

    @BeforeEach
    void setUp() {
        inputValidator = mock(InputValidator.class);
        reportParameterTypeResolver = mock(ReportParameterTypeResolver.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        context = mock(PlatformSecurityContext.class);
        when(context.authenticatedUser()).thenReturn(mock(AppUser.class));
        service = new PentahoReportingProcessService(inputValidator, reportParameterTypeResolver, jdbcTemplate, context, tempDir);
    }

    @Test
    void shouldReturnAvailableExportTargetsForPentaho() {
        List<ReportExportType> targets = service.getAvailableExportTargets();

        assertThat(targets).extracting(ReportExportType::getKey).containsExactly("HTML", "PDF", "XLS", "CSV");
    }

    @Test
    void shouldNormalizeFormattedDateParametersAndValidate() {
        when(reportParameterTypeResolver.loadParamFormatTypes("Client Loan Account Schedule"))
                .thenReturn(Map.of("startDate", "date", "endDate", "date", "selectLoan", "string"));

        MultivaluedMap<String, String> queryParams = new MultivaluedStringMap();
        queryParams.putSingle("dateFormat", "dd MMMM yyyy");
        queryParams.putSingle("locale", "en");
        queryParams.putSingle("R_startDate", "23 November 2026");
        queryParams.putSingle("R_endDate", "30 November 2026");
        queryParams.putSingle("R_selectLoan", "000000001");

        Map<String, String> params = service.getReportParams("Client Loan Account Schedule", queryParams);

        assertThat(params).containsEntry("${startDate}", "2026-11-23");
        assertThat(params).containsEntry("${endDate}", "2026-11-30");
        assertThat(params).containsEntry("${selectLoan}", "000000001");
        verify(inputValidator).validate("date", "2026-11-23");
        verify(inputValidator).validate("date", "2026-11-30");
    }

    @Test
    void shouldGeneratePdfReceiptForLoanTransaction() {
        SqlRowSet rowSet = mockSingleRowResult(List.of("No. Recibo", "Asociado", "Monto Total"), List.of("15", "Juan Perez", "250000.00"));
        when(jdbcTemplate.queryForRowSet(argThat(sql -> sql != null && !sql.startsWith("SELECT sp.parameter_variable")),
                any(Object[].class))).thenReturn(rowSet);

        MultivaluedMap<String, String> queryParams = new MultivaluedStringMap();
        queryParams.putSingle("output-type", "PDF");
        queryParams.putSingle("R_transactionId", "15");

        Response response = service.processRequest("Loan Transaction Receipt", queryParams);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getMediaType().toString()).isEqualTo("application/pdf");
        byte[] pdfBytes = (byte[]) response.getEntity();
        assertThat(new String(pdfBytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void shouldGenerateHtmlAndXlsAndCsvOutputs() throws IOException {
        SqlRowSet htmlRowSet = mockSingleRowResult(List.of("No. Cuenta", "Monto"), List.of("0001", "1000.00"));
        SqlRowSet xlsRowSet = mockSingleRowResult(List.of("No. Cuenta", "Monto"), List.of("0001", "1000.00"));
        SqlRowSet csvRowSet = mockSingleRowResult(List.of("No. Cuenta", "Monto"), List.of("0001", "1000.00"));
        when(jdbcTemplate.queryForRowSet(argThat(sql -> sql != null && !sql.startsWith("SELECT sp.parameter_variable")),
                any(Object[].class))).thenReturn(htmlRowSet, xlsRowSet, csvRowSet);

        MultivaluedMap<String, String> htmlParams = new MultivaluedStringMap();
        htmlParams.putSingle("output-type", "HTML");
        htmlParams.putSingle("R_transactionId", "1");
        Response htmlResponse = service.processRequest("Savings Transaction Receipt", htmlParams);
        assertThat(htmlResponse.getStatus()).isEqualTo(200);
        assertThat(htmlResponse.getMediaType().toString()).startsWith("text/html");
        assertThat((String) htmlResponse.getEntity()).contains("FONDO DE EMPLEADOS FAC FUNDEVI").contains("1,000.00");

        MultivaluedMap<String, String> xlsParams = new MultivaluedStringMap();
        xlsParams.putSingle("output-type", "XLS");
        xlsParams.putSingle("R_transactionId", "1");
        Response xlsResponse = service.processRequest("Savings Transaction Receipt", xlsParams);
        assertThat(xlsResponse.getStatus()).isEqualTo(200);
        assertThat(xlsResponse.getMediaType().toString()).isEqualTo("application/vnd.ms-excel");
        byte[] xlsBytes = (byte[]) xlsResponse.getEntity();
        try (HSSFWorkbook wb = new HSSFWorkbook(new ByteArrayInputStream(xlsBytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(1);
        }

        MultivaluedMap<String, String> csvParams = new MultivaluedStringMap();
        csvParams.putSingle("output-type", "CSV");
        csvParams.putSingle("R_transactionId", "1");
        Response csvResponse = service.processRequest("Savings Transaction Receipt", csvParams);
        assertThat(csvResponse.getStatus()).isEqualTo(200);
        assertThat(csvResponse.getMediaType().toString()).startsWith("text/csv");
        assertThat((String) csvResponse.getEntity()).contains("No. Cuenta,Monto").contains("\"1,000.00\"");
    }

    @Test
    void shouldFormatDecimalAmountsWithThousandsSeparatorsAndTwoDecimals() {
        SqlRowSet rowSet = mockSingleRowResult(List.of("No. Recibo", "Monto Total", "Saldo Préstamo"),
                List.of("15", "250000.00", "1000000.000000"));
        when(jdbcTemplate.queryForRowSet(argThat(sql -> sql != null && !sql.startsWith("SELECT sp.parameter_variable")),
                any(Object[].class))).thenReturn(rowSet);

        MultivaluedMap<String, String> params = new MultivaluedStringMap();
        params.putSingle("output-type", "HTML");
        params.putSingle("R_transactionId", "15");

        Response response = service.processRequest("Loan Transaction Receipt", params);

        assertThat(response.getStatus()).isEqualTo(200);
        String html = (String) response.getEntity();
        assertThat(html).contains(">15<").contains("250,000.00").contains("1,000,000.00");
    }

    @Test
    void shouldReadCustomSqlFromPrptArchiveOnDisk() throws IOException {
        Path prptFile = tempDir.resolve("CustomReport.prpt");
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <data:sql-datasource xmlns:data="http://reporting.pentaho.org/namespaces/engine/classic/extensions/datasources/sql/1.0">
                  <data:query name="Main">SELECT id AS "ID" FROM m_client WHERE id = ${clientId}</data:query>
                </data:sql-datasource>
                """;
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(prptFile))) {
            zos.putNextEntry(new ZipEntry("datasources/sql-ds.xml"));
            zos.write(xml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        SqlRowSet rowSet = mockSingleRowResult(List.of("ID"), List.of("42"));
        when(jdbcTemplate.queryForRowSet(eq("SELECT id AS \"ID\" FROM m_client WHERE id = ?"), any(Object[].class))).thenReturn(rowSet);

        MultivaluedMap<String, String> params = new MultivaluedStringMap();
        params.putSingle("output-type", "CSV");
        params.putSingle("R_clientId", "42");

        Response response = service.processRequest("CustomReport", params);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat((String) response.getEntity()).contains("ID").contains("42");
        verify(jdbcTemplate).queryForRowSet("SELECT id AS \"ID\" FROM m_client WHERE id = ?", new Object[] { "42" });
    }

    @Test
    void shouldMarkDatatableReportingProcessServiceAsPrimaryBean() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        GenericBeanDefinition datatableDef = new GenericBeanDefinition();
        datatableDef.setBeanClass(DatatableReportingProcessService.class);
        beanFactory.registerBeanDefinition("datatableReportingProcessService", datatableDef);

        BeanFactoryPostProcessor postProcessor = PentahoReportingProcessService.datatableReportingPrimaryPostProcessor();
        postProcessor.postProcessBeanFactory(beanFactory);

        assertThat(beanFactory.getBeanDefinition("datatableReportingProcessService").isPrimary()).isTrue();
    }

    private SqlRowSet mockSingleRowResult(List<String> headers, List<String> values) {
        SqlRowSet rowSet = mock(SqlRowSet.class);
        SqlRowSetMetaData metaData = mock(SqlRowSetMetaData.class);
        when(rowSet.getMetaData()).thenReturn(metaData);
        when(metaData.getColumnCount()).thenReturn(headers.size());
        for (int i = 0; i < headers.size(); i++) {
            when(metaData.getColumnLabel(i + 1)).thenReturn(headers.get(i));
            when(rowSet.getObject(i + 1)).thenReturn(values.get(i));
        }
        when(rowSet.next()).thenReturn(true, false);
        return rowSet;
    }
}
