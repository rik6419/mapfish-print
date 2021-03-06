package org.mapfish.print.processor.map.scalebar;

import com.google.common.collect.Lists;

import net.sf.jasperreports.engine.JRException;

import org.mapfish.print.attribute.ScalebarAttribute;
import org.mapfish.print.attribute.map.MapfishMapContext;
import org.mapfish.print.config.Configuration;
import org.mapfish.print.config.Template;
import org.mapfish.print.processor.AbstractProcessor;
import org.mapfish.print.processor.InternalValue;
import org.mapfish.print.processor.jasper.ImagesSubReport;
import org.mapfish.print.processor.jasper.JasperReportBuilder;

import java.awt.Dimension;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;

/**
 * <p>Processor to create a scalebar for a map.</p>
 * <p>See also: <a href="attributes.html#!scalebar">!scalebar</a> attribute</p>
 * [[examples=verboseExample,print_osm_new_york_EPSG_900913]]
 */
public class CreateScalebarProcessor extends AbstractProcessor<CreateScalebarProcessor.Input, CreateScalebarProcessor.Output> {

    /**
     * Constructor.
     */
    protected CreateScalebarProcessor() {
        super(Output.class);
    }

    @Override
    protected void extraValidation(final List<Throwable> validationErrors, final Configuration configuration) {
    }

    @Override
    public final Input createInputParameter() {
        return new Input();
    }

    @Override
    public final Output execute(final Input values, final ExecutionContext context) throws Exception {
        checkCancelState(context);

        final URI scalebarGraphicFile = createScalebarGraphic(values);

        checkCancelState(context);
        final URI scalebarSubReport = createScalebarSubReport(
                values.tempTaskDirectory, values.scalebar.getSize(),
                Lists.newArrayList(scalebarGraphicFile), values.mapContext.getDPI());

        return new Output(scalebarGraphicFile, scalebarSubReport.toString());
    }

    private URI createScalebarGraphic(final Input values) throws IOException, ParserConfigurationException {
        final ScalebarGraphic scalebar = new ScalebarGraphic();
        return scalebar.render(values.mapContext, values.scalebar, values.tempTaskDirectory, values.template);
    }

    private URI createScalebarSubReport(final File printDirectory,
                                   final Dimension size,
                                   final List<URI> graphics,
                                   final double dpi) throws IOException, JRException {
        final ImagesSubReport subReport = new ImagesSubReport(graphics, size, dpi);

        final File compiledReport = File.createTempFile("scalebar-report-",
                JasperReportBuilder.JASPER_REPORT_COMPILED_FILE_EXT, printDirectory);
        subReport.compile(compiledReport);

        return compiledReport.toURI();
    }

    /**
     * Input for the processor.
     */
    public static class Input {

        /**
         * The map the scalebar is created for.
         */
        public MapfishMapContext mapContext;

        /**
         * The parameters for the scalebar.
         */
        public ScalebarAttribute.ScalebarAttributeValues scalebar;

        /**
         * The path to the temporary directory for the print task.
         */
        @InternalValue
        public File tempTaskDirectory;
        /**
         * The containing template.
         */
        @InternalValue
        public Template template;
    }

    /**
     * Output for the processor.
     */
    public static final class Output {

        /**
         * The path to the scalebar graphic (for testing purposes).
         */
        @InternalValue
        public final URI graphic;

        /**
         * The path to the compiled sub-report for the scalebar.
         */
        public final String scalebarSubReport;

        private Output(final URI graphic, final String subReport) {
            this.graphic = graphic;
            this.scalebarSubReport = subReport;
        }
    }
}
