package de.smartdata.porter.importer.parser.fileanalyser;

import de.smartdata.porter.importer.Importer;
import de.smartdata.porter.importer.descriptors.SourceDescriptor;
import jakarta.json.JsonObjectBuilder;

/**
 *
 * @author Florian
 */
public interface MetadataExtractorModule {
    boolean checkApplicability(SourceDescriptor desc, String mimeType);
    void extractMetadata(SourceDescriptor desc,
                         JsonObjectBuilder json,
                         Importer importer) throws Exception;

}

