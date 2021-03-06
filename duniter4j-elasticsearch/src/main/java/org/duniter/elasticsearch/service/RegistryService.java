package org.duniter.elasticsearch.service;

/*
 * #%L
 * UCoin Java Client :: Core API
 * %%
 * Copyright (C) 2014 - 2015 EIS
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the 
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public 
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.apache.commons.lang3.ArrayUtils;
import org.duniter.core.client.model.bma.BlockchainBlock;
import org.duniter.core.client.model.bma.BlockchainParameters;
import org.duniter.core.client.model.bma.gson.GsonUtils;
import org.duniter.core.client.model.elasticsearch.Currency;
import org.duniter.core.client.model.local.Peer;
import org.duniter.core.client.service.bma.BlockchainRemoteService;
import org.duniter.core.client.service.bma.WotRemoteService;
import org.duniter.core.exception.TechnicalException;
import org.duniter.core.service.CryptoService;
import org.duniter.core.util.ObjectUtils;
import org.duniter.core.util.StringUtils;
import org.duniter.elasticsearch.PluginSettings;
import org.duniter.elasticsearch.exception.AccessDeniedException;
import org.duniter.elasticsearch.exception.DuplicateIndexIdException;
import org.duniter.elasticsearch.exception.InvalidSignatureException;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequestBuilder;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchPhaseExecutionException;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHitField;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Objects;

/**
 * Created by Benoit on 30/03/2015.
 */
public class RegistryService extends AbstractService {

    public static final String INDEX = "registry";
    public static final String RECORD_TYPE = "record";
    public static final String RECORD_CATEGORY_TYPE = "category";
    public static final String RECORD_COMMENT_TYPE = "comment";
    public static final String CURRENCY_TYPE = "currency";
    private static final String CATEGORIES_BULK_CLASSPATH_FILE = "registry-categories-bulk-insert.json";

    private final Gson gson;
    private BlockchainRemoteService blockchainRemoteService;

    @Inject
    public RegistryService(Client client,
                           PluginSettings settings,
                           WotRemoteService wotRemoteService,
                           CryptoService cryptoService,
                           BlockchainRemoteService blockchainRemoteService) {
        super("gchange." + INDEX, client, settings, cryptoService);
        gson = GsonUtils.newBuilder().create();
        this.blockchainRemoteService = blockchainRemoteService;
    }

    /**
     * Create index need for blockchain registry, if need
     */
    public RegistryService createIndexIfNotExists() {
        try {
            if (!existsIndex(INDEX)) {
                createIndex();

                fillRecordCategories();
            }
        }
        catch(JsonProcessingException e) {
            throw new TechnicalException(String.format("Error while creating index [%s]", INDEX));
        }
        return this;
    }

    /**
     * Create index for registry
     * @throws JsonProcessingException
     */
    public RegistryService createIndex() throws JsonProcessingException {
        logger.info(String.format("Creating index [%s]", INDEX));

        CreateIndexRequestBuilder createIndexRequestBuilder = client.admin().indices().prepareCreate(INDEX);
        org.elasticsearch.common.settings.Settings indexSettings = org.elasticsearch.common.settings.Settings.settingsBuilder()
                .put("number_of_shards", 3)
                .put("number_of_replicas", 1)
                //.put("analyzer", createDefaultAnalyzer())
                .build();
        createIndexRequestBuilder.setSettings(indexSettings);
        createIndexRequestBuilder.addMapping(CURRENCY_TYPE, createCurrencyType());
        createIndexRequestBuilder.addMapping(RECORD_CATEGORY_TYPE, createRecordCategoryType());
        createIndexRequestBuilder.addMapping(RECORD_TYPE, createRecordType());
        createIndexRequestBuilder.addMapping(RECORD_COMMENT_TYPE, createRecordCommentType(INDEX, RECORD_COMMENT_TYPE));
        createIndexRequestBuilder.execute().actionGet();

        return this;
    }

    public RegistryService deleteIndex() {
        deleteIndexIfExists(INDEX);
        return this;
    }

    public boolean existsIndex() {
        return super.existsIndex(INDEX);
    }

    public RegistryService fillRecordCategories() {
        if (logger.isDebugEnabled()) {
            logger.debug(String.format("[%s/%s] Fill data", INDEX, RECORD_CATEGORY_TYPE));
        }

        // Insert categories
        bulkFromClasspathFile(CATEGORIES_BULK_CLASSPATH_FILE, INDEX, RECORD_CATEGORY_TYPE,
                // Add order attribute
                new AddSequenceAttributeHandler("order", "\\{.*\"name\".*\\}", 1));

        return this;
    }

    public boolean isCurrencyExists(String currencyName) {
        String pubkey = getSenderPubkeyByCurrencyId(currencyName);
        return !StringUtils.isEmpty(pubkey);
    }

    /**
     *
     * @param recordJson
     * @return the record id
     */
    public String indexRecordFromJson(String recordJson) {

        JsonNode actualObj = readAndVerifyIssuerSignature(recordJson);
        String issuer = getIssuer(actualObj);

        if (logger.isDebugEnabled()) {
            logger.debug(String.format("Indexing a registry record from issuer [%s]", issuer.substring(0, 8)));
        }

        IndexResponse response = client.prepareIndex(INDEX, RECORD_TYPE)
                .setSource(recordJson)
                .setRefresh(false)
                .execute().actionGet();
        return response.getId();
    }

    public void updateRecordFromJson(String recordJson, String id) {

        JsonNode actualObj = readAndVerifyIssuerSignature(recordJson);
        String issuer = getIssuer(actualObj);

        // Check same document issuer
        checkSameDocumentIssuer(INDEX, RECORD_TYPE, id, issuer);

        if (logger.isDebugEnabled()) {
            logger.debug(String.format("Updating market record [%s] from issuer [%s]", id, issuer.substring(0, 8)));
        }

        client.prepareUpdate(INDEX, RECORD_TYPE, id)
                .setDoc(recordJson)
                .execute().actionGet();
    }

    public void insertRecordFromBulkFile(File bulkFile) {

        if (logger.isDebugEnabled()) {
            logger.debug("Inserting records from file");
        }

        // Insert cities
        bulkFromFile(bulkFile, INDEX, RECORD_TYPE);
    }

    /**
     * Retrieve the blockchain data, from peer
     *
     * @param peer
     * @return the created blockchain
     */
    public Currency indexCurrencyFromPeer(Peer peer) {
        BlockchainParameters parameters = blockchainRemoteService.getParameters(peer);
        BlockchainBlock firstBlock = blockchainRemoteService.getBlock(peer, 0);
        BlockchainBlock currentBlock = blockchainRemoteService.getCurrentBlock(peer);
        long lastUD = blockchainRemoteService.getLastUD(peer);

        Currency result = new Currency();
        result.setCurrencyName(parameters.getCurrency());
        result.setFirstBlockSignature(firstBlock.getSignature());
        result.setMembersCount(currentBlock.getMembersCount());
        result.setLastUD(lastUD);
        result.setParameters(parameters);
        result.setPeers(new Peer[]{peer});

        indexCurrency(result);

        return result;
    }

    /**
     * Index a blockchain
     * @param currency
     */
    public void indexCurrency(Currency currency) {
        try {
            ObjectUtils.checkNotNull(currency.getCurrencyName());

            // Fill tags
            if (ArrayUtils.isEmpty(currency.getTags())) {
                String currencyName = currency.getCurrencyName();
                String[] tags = currencyName.split(REGEX_WORD_SEPARATOR);
                List<String> tagsList = Lists.newArrayList(tags);

                // Convert as a sentence (replace seprator with a space)
                String sentence = currencyName.replaceAll(REGEX_WORD_SEPARATOR, " ");
                if (!tagsList.contains(sentence)) {
                    tagsList.add(sentence);
                }

                currency.setTags(tagsList.toArray(new String[tagsList.size()]));
            }

            // Serialize into JSON
            byte[] json = objectMapper.writeValueAsBytes(currency);

            // Preparing indexBlocksFromNode
            IndexRequestBuilder indexRequest = client.prepareIndex(INDEX, CURRENCY_TYPE)
                    .setId(currency.getCurrencyName())
                    .setSource(json);

            // Execute indexBlocksFromNode
            indexRequest
                    .setRefresh(true)
                    .execute().actionGet();

        } catch(JsonProcessingException e) {
            throw new TechnicalException(e);
        }
    }

    /**
     * Get suggestions from a string query. Useful for web autocomplete field (e.g. text full search)
     * @param query
     * @return
     */
   /* public List<String> getSuggestions(String query) {
        CompletionSuggestionBuilder suggestionBuilder = new CompletionSuggestionBuilder(INDEX_TYPE)
                .text(query)
                .size(10) // limit to 10 results
                .field("tags");

        // Prepare request
        SuggestRequestBuilder suggestRequest = client
                .prepareSuggest(INDEX_NAME)
                .addSuggestion(suggestionBuilder);

        // Execute query
        SuggestResponse response = suggestRequest.execute().actionGet();

        // Read query result
        return toSuggestions(response, RECORD_CATEGORY_TYPE, query);
    }*/

    /**
     * Save a blockchain (update or create) into the blockchain index.
     * @param currency
     * @param senderPubkey
     * @throws DuplicateIndexIdException
     * @throws AccessDeniedException if exists and user if not the original blockchain sender
     */
    public void saveCurrency(Currency currency, String senderPubkey) throws DuplicateIndexIdException {
        ObjectUtils.checkNotNull(currency, "currency could not be null") ;
        ObjectUtils.checkNotNull(currency.getCurrencyName(), "currency attribute 'currencyName' could not be null");

        String previousSenderPubkey = getSenderPubkeyByCurrencyId(currency.getCurrencyName());

        // Currency not exists, so create it
        if (previousSenderPubkey == null) {
            // make sure to fill the sender
            currency.setSenderPubkey(senderPubkey);

            // Save it
            indexCurrency(currency);
        }

        // Exists, so check the owner signature
        else {
            if (!Objects.equals(senderPubkey, previousSenderPubkey)) {
                throw new AccessDeniedException("Could not change the currency, because it has been registered by another public key.");
            }

            // Make sure the sender is not changed
            currency.setSenderPubkey(previousSenderPubkey);

            // Save changes
            indexCurrency(currency);
        }
    }

    /**
     * Registrer a new blockchain.
     * @param pubkey the sender pubkey
     * @param jsonCurrency the blockchain, as JSON
     * @param signature the signature of sender.
     * @throws InvalidSignatureException if signature not correspond to sender pubkey
     */
    public void insertCurrency(String pubkey, String jsonCurrency, String signature) {
        Preconditions.checkNotNull(pubkey);
        Preconditions.checkNotNull(jsonCurrency);
        Preconditions.checkNotNull(signature);

        if (!cryptoService.verify(jsonCurrency, signature, pubkey)) {
            String currencyName = GsonUtils.getValueFromJSONAsString(jsonCurrency, "currencyName");
            logger.warn(String.format("Currency not added, because bad signature. blockchain [%s]", currencyName));
            throw new InvalidSignatureException("Bad signature");
        }

        Currency currency = null;
        try {
            currency = gson.fromJson(jsonCurrency, Currency.class);
            Preconditions.checkNotNull(currency);
            Preconditions.checkNotNull(currency.getCurrencyName());
        } catch(Throwable t) {
            logger.error("Error while reading blockchain JSON: " + jsonCurrency);
            throw new TechnicalException("Error while reading blockchain JSON: " + jsonCurrency, t);
        }

        saveCurrency(currency, pubkey);
    }

    /* -- Internal methods -- */


    public XContentBuilder createRecordType() {
        String stringAnalyzer = pluginSettings.getDefaultStringAnalyzer();

        try {
            XContentBuilder mapping = XContentFactory.jsonBuilder().startObject().startObject(RECORD_TYPE)
                    .startObject("properties")

                    // title
                    .startObject("title")
                    .field("type", "string")
                    .field("analyzer", stringAnalyzer)
                    .endObject()

                    // description
                    .startObject("description")
                    .field("type", "string")
                    .field("analyzer", stringAnalyzer)
                    .endObject()

                    // time
                    .startObject("time")
                    .field("type", "integer")
                    .endObject()

                    // issuer
                    .startObject("issuer")
                    .field("type", "string")
                    .field("index", "not_analyzed")
                    .endObject()

                    // pubkey
                    .startObject("pubkey")
                    .field("type", "string")
                    .field("index", "not_analyzed")
                    .endObject()

                    // address
                    .startObject("address")
                    .field("type", "string")
                    .field("analyzer", stringAnalyzer)
                    .endObject()

                    // city
                    .startObject("city")
                    .field("type", "string")
                    .endObject()

                    // geoPoint
                    .startObject("geoPoint")
                    .field("type", "geo_point")
                    .endObject()

                    // thumbnail
                    .startObject("thumbnail")
                    .field("type", "attachment")
                    .startObject("fields") // src
                    .startObject("content") // title
                    .field("index", "no")
                    .endObject()
                    .startObject("title") // title
                    .field("type", "string")
                    .field("store", "no")
                    .endObject()
                    .startObject("author") // title
                    .field("store", "no")
                    .endObject()
                    .startObject("content_type") // title
                    .field("store", "yes")
                    .endObject()
                    .endObject()
                    .endObject()

                    // pictures
                    .startObject("pictures")
                    .field("type", "nested")
                    .field("dynamic", "false")
                    .startObject("properties")
                    .startObject("file") // file
                    .field("type", "attachment")
                    .startObject("fields")
                    .startObject("content") // content
                    .field("index", "no")
                    .endObject()
                    .startObject("title") // title
                    .field("type", "string")
                    .field("store", "yes")
                    .field("analyzer", stringAnalyzer)
                    .endObject()
                    .startObject("author") // author
                    .field("type", "string")
                    .field("store", "no")
                    .endObject()
                    .startObject("content_type") // content_type
                    .field("store", "yes")
                    .endObject()
                    .endObject()
                    .endObject()
                    .endObject()
                    .endObject()

                    // picturesCount
                    .startObject("picturesCount")
                    .field("type", "integer")
                    .endObject()

                    // category
                    .startObject("category")
                    .field("type", "nested")
                    .field("dynamic", "false")
                    .startObject("properties")
                    .startObject("id") // id
                    .field("type", "string")
                    .field("index", "not_analyzed")
                    .endObject()
                    .startObject("parent") // parent
                    .field("type", "string")
                    .field("index", "not_analyzed")
                    .endObject()
                    .startObject("name") // name
                    .field("type", "string")
                    .field("analyzer", stringAnalyzer)
                    .endObject()
                    .endObject()
                    .endObject()

                    // tags
                    .startObject("tags")
                    .field("type", "completion")
                    .field("search_analyzer", "simple")
                    .field("analyzer", "simple")
                    .field("preserve_separators", "false")
                    .endObject()

                    .endObject()
                    .endObject().endObject();

            return mapping;
        }
        catch(IOException ioe) {
            throw new TechnicalException(String.format("Error while getting mapping for index [%s/%s]: %s", INDEX, RECORD_TYPE, ioe.getMessage()), ioe);
        }
    }

    public XContentBuilder createRecordCategoryType() {
        try {
            XContentBuilder mapping = XContentFactory.jsonBuilder().startObject().startObject(RECORD_CATEGORY_TYPE)
                    .startObject("properties")

                    // name
                    .startObject("name")
                    .field("type", "string")
                    .field("analyzer", pluginSettings.getDefaultStringAnalyzer())
                    .endObject()

                    // description
                    /*.startObject("description")
                    .field("type", "string")
                    .endObject()*/

                    // parent
                    .startObject("parent")
                    .field("type", "string")
                    .field("index", "not_analyzed")
                    .endObject()

                    // tags
                    /*.startObject("tags")
                    .field("type", "completion")
                    .field("search_analyzer", "simple")
                    .field("analyzer", "simple")
                    .field("preserve_separators", "false")
                    .endObject()*/

                    .endObject()
                    .endObject().endObject();

            return mapping;
        }
        catch(IOException ioe) {
            throw new TechnicalException(String.format("Error while getting mapping for index [%s/%s]: %s", INDEX, RECORD_CATEGORY_TYPE, ioe.getMessage()), ioe);
        }
    }

    public XContentBuilder createCurrencyType() {
        try {
            XContentBuilder mapping = XContentFactory.jsonBuilder().startObject().startObject(CURRENCY_TYPE)
                    .startObject("properties")

                    // blockchain name
                    .startObject("currencyName")
                    .field("type", "string")
                    .endObject()

                    // member count
                    .startObject("membersCount")
                    .field("type", "long")
                    .endObject()

                    // tags
                    .startObject("tags")
                    .field("type", "completion")
                    .field("search_analyzer", "simple")
                    .field("analyzer", "simple")
                    .field("preserve_separators", "false")

                    .endObject()
                    .endObject()
                    .endObject().endObject();

            return mapping;
        }
        catch(IOException ioe) {
            throw new TechnicalException(String.format("Error while getting mapping for index [%s/%s]: %s", INDEX, CURRENCY_TYPE, ioe.getMessage()), ioe);
        }
    }

    /**
     *
     * @param jsonCategory
     * @return the product id
     */
    public String indexCategoryFromJson(String jsonCategory) {
        if (logger.isDebugEnabled()) {
            logger.debug("Indexing a category");
        }

        // Preparing indexBlocksFromNode
        IndexRequestBuilder indexRequest = client.prepareIndex(INDEX, RECORD_CATEGORY_TYPE)
                .setSource(jsonCategory);

        // Execute indexBlocksFromNode
        IndexResponse response = indexRequest
                .setRefresh(false)
                .execute().actionGet();

        return response.getId();
    }



    /**
     * Retrieve a blockchain from its name
     * @param currencyId
     * @return
     */
    protected String getSenderPubkeyByCurrencyId(String currencyId) {

        if (!existsIndex(currencyId)) {
            return null;
        }

        // Prepare request
        SearchRequestBuilder searchRequest = client
                .prepareSearch(INDEX)
                .setTypes(CURRENCY_TYPE)
                .setSearchType(SearchType.DFS_QUERY_THEN_FETCH);

        // If more than a word, search on terms match
        searchRequest.setQuery(QueryBuilders.matchQuery("_id", currencyId));

        // Execute query
        try {
            SearchResponse response = searchRequest.execute().actionGet();

            // Read query result
            SearchHit[] searchHits = response.getHits().getHits();
            for (SearchHit searchHit : searchHits) {
                if (searchHit.source() != null) {
                    Currency currency = gson.fromJson(new String(searchHit.source(), "UTF-8"), Currency.class);
                    return currency.getSenderPubkey();
                }
                else {
                    SearchHitField field = searchHit.getFields().get("senderPubkey");
                    return field.getValue().toString();
                }
            }
        }
        catch(SearchPhaseExecutionException | JsonSyntaxException | UnsupportedEncodingException e) {
            // Failed or no item on index
        }

        return null;
    }
}
