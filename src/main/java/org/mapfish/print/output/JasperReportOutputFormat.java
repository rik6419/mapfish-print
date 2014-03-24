/*
 * Copyright (C) 2014  Camptocamp
 *
 * This file is part of MapFish Print
 *
 * MapFish Print is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * MapFish Print is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with MapFish Print.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.mapfish.print.output;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JREmptyDataSource;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource;
import org.mapfish.print.Constants;
import org.mapfish.print.attribute.Attribute;
import org.mapfish.print.config.Configuration;
import org.mapfish.print.config.Template;
import org.mapfish.print.json.PJsonObject;
import org.mapfish.print.processor.Processor;
import org.mapfish.print.processor.ProcessorDependencyGraphFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ForkJoinPool;

/**
 * An PDF output format that uses Jasper reports to generate the result.
 *
 * @author Jesse
 * @author sbrunner
 */
public class JasperReportOutputFormat implements OutputFormat {
    @SuppressWarnings("unused")
    private static final Logger LOGGER = LoggerFactory.getLogger(JasperReportOutputFormat.class);
    @Autowired
    private ForkJoinPool forkJoinPool;
    @Autowired
    private ProcessorDependencyGraphFactory processorGraphFactory;


    @Override
    public final String getContentType() {
        return "application/pdf";
    }

    @Override
    public final String getFileSuffix() {
        return "pdf";
    }

    @Override
    public final void print(final PJsonObject spec, final Configuration config, final File configDir, final OutputStream outputStream)
            throws Exception {
        final String templateName = spec.getString(Constants.JSON_LAYOUT_KEY);

        final Template template = config.getTemplate(templateName);
        final Values values = new Values();
        final File jasperTemplateFile = new File(configDir, template.getJasperTemplate());
        final File jasperTemplateBuild = new File(configDir, template.getJasperTemplate().replaceAll("\\.jrxml$", ".jasper"));
        final File jasperTemplateDirectory = jasperTemplateFile.getParentFile();

        values.put("SUBREPORT_DIR", jasperTemplateDirectory.getAbsolutePath());

        final PJsonObject jsonAttributes = spec.getJSONObject("attributes");

        Map<String, Attribute<?>> attributes = template.getAttributes();
        for (String attributeName : attributes.keySet()) {
            values.put(attributeName, attributes.get(attributeName).
                    getValue(jsonAttributes, attributeName));

        }

        this.forkJoinPool.invoke(template.getProcessorGraph(this.processorGraphFactory).createTask(values));

        if (template.getIterValue() != null) {
            List<Map<String, ?>> dataSource = new ArrayList<Map<String, ?>>();
            Iterable<Values> iter = values.getIterator(template.getIterValue());
            for (Values iterValues : iter) {
                this.forkJoinPool.invoke(template.getIterProcessorGraph(this.processorGraphFactory).createTask(values));
                dataSource.add(iterValues.getParameters());
            }
            final JRDataSource jrDataSource = new JRMapCollectionDataSource(dataSource);
            final JasperPrint print = JasperFillManager.fillReport(
                    jasperTemplateBuild.getAbsolutePath(),
                    values.getParameters(),
                    jrDataSource);
            JasperExportManager.exportReportToPdfStream(print, outputStream);
        } else if (template.getJdbcUrl() != null && template.getJdbcUser() != null && template.getJdbcPassword() != null) {
            Connection connection = DriverManager.getConnection(
                    template.getJdbcUrl(), template.getJdbcUser(), template.getJdbcPassword());
            final JasperPrint print = JasperFillManager.fillReport(
                    jasperTemplateBuild.getAbsolutePath(),
                    values.getParameters(),
                    connection);
            JasperExportManager.exportReportToPdfStream(print, outputStream);
        } else if (template.getJdbcUrl() != null) {
            Connection connection = DriverManager.getConnection(template.getJdbcUrl());
            final JasperPrint print = JasperFillManager.fillReport(
                    jasperTemplateBuild.getAbsolutePath(),
                    values.getParameters(),
                    connection);
            JasperExportManager.exportReportToPdfStream(print, outputStream);
        } else {
            final JasperPrint print = JasperFillManager.fillReport(
                    jasperTemplateBuild.getAbsolutePath(),
                    values.getParameters(),
                    new JREmptyDataSource());
            JasperExportManager.exportReportToPdfStream(print, outputStream);
        }
    }


    private void runProcess(final Processor process, final Values values) throws Exception {
        Map<String, Object> input = new HashMap<String, Object>();
        Map<String, String> inputMap = process.getInputMapper();
        for (String value : inputMap.keySet()) {
            input.put(
                    inputMap.get(value),
                    values.getObject(value, Object.class));
        }

        Map<String, Object> output = process.execute(input);
        Map<String, String> outputMap = process.getOutputMapper();
        for (String value : outputMap.keySet()) {
            values.put(
                    outputMap.get(value),
                    output.get(value));
        }
    }

    /**
     * The runnable that process the prossessor.
     *
     * @author sbrunner
     */
    private class ProcessRun implements Runnable {
        private final Map<Processor, List<Processor>> required;
        private final Queue<Processor> ready;
        private final Values values;
        private boolean stopped = false;
        private boolean errored = false;

        private static final int STEEP_TIME = 1000;

        public ProcessRun(final Map<Processor, List<Processor>> required,
                          final Queue<Processor> ready, final Values values) {
            this.required = required;
            this.ready = ready;
            this.values = values;
        }

        @Override
        public void run() {
            while (!this.stopped) {
                Processor p = this.ready.poll();
                if (p != null) {
                    try {
                        runProcess(p, this.values);

                        for (Processor proc : this.required.keySet()) {
                            List<Processor> procList = this.required.get(proc);
                            procList.remove(p);
                            if (procList.isEmpty()) {
                                this.ready.add(proc);
                                this.required.remove(proc);
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        this.errored = true;
                        return;
                    }
                } else {
                    if (!this.required.isEmpty()) {
                        try {
                            Thread.sleep(STEEP_TIME);
                        } catch (InterruptedException e) {
                            // continue
                        }
                    } else {
                        return; // finish
                    }
                }
            }
        }
    }
}
