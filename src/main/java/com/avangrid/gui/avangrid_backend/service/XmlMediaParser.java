package com.avangrid.gui.avangrid_backend.service;

import com.avangrid.gui.avangrid_backend.exception.RecordingProcessingException;
import com.avangrid.gui.avangrid_backend.model.MediaMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Parser for VPI XML media metadata files.
 *
 * <p>Supports two XML formats:
 * <ul>
 *   <li>CMP format: ExportSummary > Objects > Media</li>
 *   <li>NYSEG/RGE format: Direct Media element</li>
 * </ul>
 *
 * <p>Implements secure XML parsing to prevent XXE and other attacks.
 *
 * @author Avangrid Backend Team
 * @version 1.0
 */
@Component
public class XmlMediaParser {

    private static final Logger logger = LoggerFactory.getLogger(XmlMediaParser.class);

    // XML element tags
    private static final String MEDIA_TAG = "Media";
    private static final String OBJECTS_TAG = "Objects";
    private static final String EXPORT_SUMMARY_TAG = "ExportSummary";

    // XML attribute keys
    private static final String ATTR_FILE_NAME = "FileName";
    private static final String ATTR_TYPE = "Type";
    private static final String ATTR_RESULT = "Result";

    // Security features
    @SuppressWarnings("java:S1075")
    private static final String FEATURE_DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl";
    @SuppressWarnings("java:S1075")
    private static final String FEATURE_EXTERNAL_GENERAL = "http://xml.org/sax/features/external-general-entities";
    @SuppressWarnings("java:S1075")
    private static final String FEATURE_EXTERNAL_PARAMETER = "http://xml.org/sax/features/external-parameter-entities";
    @SuppressWarnings("java:S1075")
    private static final String FEATURE_LOAD_EXTERNAL_DTD = "http://apache.org/xml/features/nonvalidating/load-external-dtd";

    private final DocumentBuilderFactory factory;

    /**
     * Constructs a new XmlMediaParser with secure XML parsing configuration.
     *
     * @throws RecordingProcessingException if factory configuration fails
     */
    public XmlMediaParser() {
        this.factory = DocumentBuilderFactory.newInstance();
        configureSecureFactory();
    }

    /**
     * Configures the XML factory with security best practices to prevent:
     * <ul>
     *   <li>XML External Entity (XXE) attacks</li>
     *   <li>A Billion Laughs attack</li>
     *   <li>External DTD loading</li>
     * </ul>
     *
     * @throws RecordingProcessingException if configuration fails
     */
    private void configureSecureFactory() {
        try {
            // Disable DOCTYPE declarations to prevent XXE attacks
            factory.setFeature(FEATURE_DISALLOW_DOCTYPE, true);

            // Disable external entities
            factory.setFeature(FEATURE_EXTERNAL_GENERAL, false);
            factory.setFeature(FEATURE_EXTERNAL_PARAMETER, false);

            // Disable external DTD loading
            factory.setFeature(FEATURE_LOAD_EXTERNAL_DTD, false);

            // Disable XInclude processing
            factory.setXIncludeAware(false);

            // Disable entity expansion
            factory.setExpandEntityReferences(false);

            // Set secure processing
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

            logger.debug("XML parser configured with security features enabled");

        } catch (ParserConfigurationException e) {
            logger.error("Failed to configure secure XML parser", e);
            throw new RecordingProcessingException("Failed to configure XML parser", e);
        }
    }

    /**
     * Parses XML stream and extracts media metadata.
     *
     * <p>Supports both CMP format (ExportSummary wrapper with multiple Media elements)
     * and NYSEG/RGE format (single Media element).
     *
     * @param xmlStream the XML input stream (must not be null)
     * @return list of MediaMetadata objects (never null, may be empty)
     * @throws RecordingProcessingException if parsing fails or stream is invalid
     */
    public List<MediaMetadata> parse(InputStream xmlStream) {

        if (xmlStream == null) {
            throw new RecordingProcessingException("XML input stream cannot be null");
        }

        try {
            DocumentBuilder builder = factory.newDocumentBuilder();

            // Disable external entity resolution at builder level (defense in depth)
            builder.setEntityResolver((publicId, systemId) -> {
                logger.warn("Attempted to resolve external entity - blocking: {}", systemId);
                throw new SAXException("External entity resolution is disabled");
            });

            Document document = builder.parse(xmlStream);
            document.getDocumentElement().normalize();

            List<MediaMetadata> results = extractMediaMetadata(document);
            logger.debug("Successfully parsed {} media metadata records", results.size());

            return results;

        } catch (ParserConfigurationException e) {
            logger.error("XML parser configuration error", e);
            throw new RecordingProcessingException("XML parser configuration failed", e);
        } catch (SAXException e) {
            logger.error("XML parsing error - invalid XML structure", e);
            throw new RecordingProcessingException("XML parsing failed - invalid XML", e);
        } catch (IOException e) {
            logger.error("IO error while reading XML stream", e);
            throw new RecordingProcessingException("Failed to read XML stream", e);
        }
    }

    /**
     * Extracts MediaMetadata from parsed XML document.
     *
     * <p>Handles two formats:
     * <ul>
     *   <li>CMP: ExportSummary > Objects > Media[]</li>
     *   <li>NYSEG/RGE: Media (direct root element)</li>
     * </ul>
     *
     * @param document the parsed XML document
     * @return list of extracted MediaMetadata
     * @throws RecordingProcessingException if document structure is invalid
     */
    private List<MediaMetadata> extractMediaMetadata(Document document) {
        Element root = document.getDocumentElement();
        String rootTag = root.getTagName();

        logger.debug("Processing XML with root element: {}", rootTag);

        // CMP format: ExportSummary wrapper
        if (EXPORT_SUMMARY_TAG.equals(rootTag)) {
            return parseExportSummaryFormat(root);
        }

        // NYSEG/RGE format: Direct Media element
        if (MEDIA_TAG.equals(rootTag)) {
            return Collections.singletonList(buildMetadata(root));
        }

        logger.error("Invalid XML root element: {}", rootTag);
        throw new RecordingProcessingException(
                String.format("Invalid XML: Root element must be '%s' or '%s', found: %s",
                        EXPORT_SUMMARY_TAG, MEDIA_TAG, rootTag)
        );
    }

    /**
     * Parses CMP format with ExportSummary > Objects > Media structure.
     *
     * @param exportSummary the ExportSummary root element
     * @return list of MediaMetadata from all Media elements
     * @throws RecordingProcessingException if required structure is missing
     */
    private List<MediaMetadata> parseExportSummaryFormat(Element exportSummary) {
        NodeList objectsNodes = exportSummary.getElementsByTagName(OBJECTS_TAG);

        if (objectsNodes.getLength() == 0) {
            logger.error("Missing Objects element in ExportSummary");
            throw new RecordingProcessingException("Invalid XML: Missing Objects element in ExportSummary");
        }

        Node firstNode = objectsNodes.item(0);
        if (firstNode.getNodeType() != Node.ELEMENT_NODE) {
            throw new RecordingProcessingException("Invalid XML: Objects is not an element node");
        }

        Element objectsElement = (Element) firstNode;
        NodeList mediaNodes = objectsElement.getElementsByTagName(MEDIA_TAG);

        if (mediaNodes.getLength() == 0) {
            logger.debug("No Media elements found in Objects");
            return Collections.emptyList();
        }

        logger.debug("Found {} Media elements", mediaNodes.getLength());

        return IntStream.range(0, mediaNodes.getLength())
                .mapToObj(mediaNodes::item)
                .filter(node -> node.getNodeType() == Node.ELEMENT_NODE)
                .map(node -> (Element) node)
                .map(this::buildMetadata)
                .collect(Collectors.toList());
    }

    /**
     * Builds a MediaMetadata object from a Media XML element.
     *
     * <p>Extracts:
     * <ul>
     *   <li>Attributes: FileName, Type, Result</li>
     *   <li>Child elements: All mapped to dynamic fields</li>
     * </ul>
     *
     * @param mediaElement the Media XML element
     * @return populated MediaMetadata object
     */
    private MediaMetadata buildMetadata(Element mediaElement) {
        MediaMetadata metadata = new MediaMetadata();

        // Extract standard attributes
        metadata.setFileName(getAttributeOrEmpty(mediaElement, ATTR_FILE_NAME));
        metadata.setType(getAttributeOrEmpty(mediaElement, ATTR_TYPE));
        metadata.setResult(getAttributeOrEmpty(mediaElement, ATTR_RESULT));

        // Extract all child elements as dynamic fields
        Map<String, String> fields = extractChildElements(mediaElement);
        metadata.setFields(fields);

        logger.debug("Built metadata for file: {}", metadata.getFileName());

        return metadata;
    }

    /**
     * Extracts all child elements from a parent element as key-value pairs.
     *
     * <p>Duplicate keys keep the first occurrence.
     * Empty text content is stored as empty string.
     *
     * @param parent the parent XML element
     * @return map of element name to text content (never null)
     */
    private Map<String, String> extractChildElements(Element parent) {
        NodeList children = parent.getChildNodes();

        return IntStream.range(0, children.getLength())
                .mapToObj(children::item)
                .filter(node -> node.getNodeType() == Node.ELEMENT_NODE)
                .collect(Collectors.toMap(
                        Node::getNodeName,
                        node -> {
                            String content = node.getTextContent();
                            return content != null ? content : "";
                        },
                        (existing, replacement) -> existing, // Keep first occurrence on duplicate
                        LinkedHashMap::new // Preserve insertion order
                ));
    }

    /**
     * Safely retrieves an XML attribute value.
     *
     * @param element the XML element
     * @param attributeName the attribute name
     * @return attribute value or empty string if not present
     */
    private String getAttributeOrEmpty(Element element, String attributeName) {
        return (element == null || attributeName == null)
                ? ""
                : element.getAttribute(attributeName);
    }


    /**
     * Validates if an input stream contains parseable VPI XML.
     *
     * <p>This is a utility method for pre-validation. It checks:
     * <ul>
     *   <li>XML is well-formed</li>
     *   <li>Root element is either ExportSummary or Media</li>
     * </ul>
     *
     * <p><strong>Note:</strong> This method consumes the input stream.
     * Use a mark/reset capable stream if you need to parse after validation.
     *
     * @param xmlStream the XML input stream (must not be null)
     * @return true if valid XML structure, false otherwise
     */
    public boolean isValidXml(InputStream xmlStream) {
        if (xmlStream == null) {
            logger.warn("Validation attempted with null input stream");
            return false;
        }

        try {
            DocumentBuilder builder = factory.newDocumentBuilder();

            // Security: disable entity resolution
            builder.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("External entity resolution is disabled");
            });

            Document document = builder.parse(xmlStream);
            Element root = document.getDocumentElement();

            if (root == null) {
                return false;
            }

            String rootTag = root.getTagName();
            boolean isValid = EXPORT_SUMMARY_TAG.equals(rootTag) || MEDIA_TAG.equals(rootTag);

            logger.debug("XML validation result: {} (root: {})", isValid, rootTag);

            return isValid;

        } catch (ParserConfigurationException | SAXException | IOException e) {
            logger.debug("XML validation failed: {}", e.getMessage());
            return false;
        }
    }
}