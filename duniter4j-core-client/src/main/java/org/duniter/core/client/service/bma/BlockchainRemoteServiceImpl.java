package org.duniter.core.client.service.bma;

/*
 * #%L
 * UCoin Java :: Core Client API
 * %%
 * Copyright (C) 2014 - 2016 EIS
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

import org.duniter.core.client.config.Configuration;
import org.duniter.core.client.model.bma.BlockchainBlock;
import org.duniter.core.client.model.bma.BlockchainMemberships;
import org.duniter.core.client.model.bma.BlockchainParameters;
import org.duniter.core.client.model.bma.Protocol;
import org.duniter.core.client.model.bma.gson.JsonArrayParser;
import org.duniter.core.client.model.local.Identity;
import org.duniter.core.client.model.local.Peer;
import org.duniter.core.client.model.local.Wallet;
import org.duniter.core.client.service.ServiceLocator;
import org.duniter.core.client.service.exception.*;
import org.duniter.core.exception.TechnicalException;
import org.duniter.core.service.CryptoService;
import org.duniter.core.util.ObjectUtils;
import org.duniter.core.util.StringUtils;
import org.duniter.core.util.cache.Cache;
import org.duniter.core.util.cache.SimpleCache;
import org.duniter.core.util.crypto.CryptoUtils;
import org.duniter.core.util.websocket.WebsocketClientEndpoint;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

public class BlockchainRemoteServiceImpl extends BaseRemoteServiceImpl implements BlockchainRemoteService {

    private static final Logger log = LoggerFactory.getLogger(BlockchainRemoteServiceImpl.class);

    private static final String JSON_DIVIDEND_ATTR = "\"dividend\":";

    public static final String URL_BASE = "/blockchain";

    public static final String URL_PARAMETERS = URL_BASE + "/parameters";

    public static final String URL_BLOCK = URL_BASE + "/block/%s";

    public static final String URL_BLOCKS_FROM = URL_BASE + "/blocks/%s/%s";

    public static final String URL_BLOCK_CURRENT = URL_BASE + "/current";

    public static final String URL_BLOCK_WITH_UD = URL_BASE + "/with/ud";

    public static final String URL_MEMBERSHIP = URL_BASE + "/membership";

    public static final String URL_MEMBERSHIP_SEARCH = URL_BASE + "/memberships/%s";


    private NetworkRemoteService networkRemoteService;

    private Configuration config;

    // Cache need for wallet refresh : iteration on wallet should not
    // execute a download of the current block
    private Cache<Long, BlockchainBlock> mCurrentBlockCache;

    // Cache on blockchain parameters
    private Cache<Long, BlockchainParameters> mParametersCache;

    private Map<URI, WebsocketClientEndpoint> blockWsEndPoints = new HashMap<>();

    public BlockchainRemoteServiceImpl() {
        super();
    }

    @Override
    public void afterPropertiesSet() {
        super.afterPropertiesSet();
        networkRemoteService = ServiceLocator.instance().getNetworkRemoteService();
        config = Configuration.instance();

        // Initialize caches
        initCaches();
    }

    @Override
    public void close() throws IOException {
        super.close();

        if (blockWsEndPoints.size() != 0) {
            for (WebsocketClientEndpoint clientEndPoint: blockWsEndPoints.values()) {
                clientEndPoint.close();
            }
            blockWsEndPoints.clear();
        }
    }

    @Override
    public BlockchainParameters getParameters(long currencyId, boolean useCache) {
        if (!useCache) {
            return getParameters(currencyId);
        } else {
            return mParametersCache.get(currencyId);
        }
    }

    @Override
    public BlockchainParameters getParameters(long currencyId) {
        // get blockchain parameter
        BlockchainParameters result = executeRequest(currencyId, URL_PARAMETERS, BlockchainParameters.class);
        return result;
    }

    @Override
    public BlockchainParameters getParameters(Peer peer) {
        // get blockchain parameter
        BlockchainParameters result = executeRequest(peer, URL_PARAMETERS, BlockchainParameters.class);
        return result;
    }

    @Override
    public BlockchainBlock getBlock(long currencyId, long number) throws BlockNotFoundException  {
        String path = String.format(URL_BLOCK, number);
        try {
            return executeRequest(currencyId, path, BlockchainBlock.class);
        }
        catch(HttpNotFoundException e) {
            throw new BlockNotFoundException(String.format("Block #%s not found", number));
        }
    }

    @Override
    public Long getBlockDividend(long currencyId, long number) throws BlockNotFoundException {
        String path = String.format(URL_BLOCK, number);
        try {
            String json = executeRequest(currencyId, path, String.class);
            return getDividendFromBlockJson(json);
        }
        catch(HttpNotFoundException e) {
            throw new BlockNotFoundException(String.format("Block #%s not found", number));
        }
    }


    @Override
    public BlockchainBlock getBlock(Peer peer, int number) throws BlockNotFoundException {
        // Get block from number
        String path = String.format(URL_BLOCK, number);
        try {
            return executeRequest(peer, path, BlockchainBlock.class);
        }
        catch(HttpNotFoundException e) {
            throw new BlockNotFoundException(String.format("Block #%s not found on peer [%s]", number, peer));
        }
    }

    @Override
    public String getBlockAsJson(Peer peer, int number) {
        // get blockchain parameter
        String path = String.format(URL_BLOCK, number);
        try {
            return executeRequest(peer, path, String.class);
        }
        catch(HttpNotFoundException e) {
            throw new BlockNotFoundException(String.format("Block #%s not found on peer [%s]", number, peer));
        }
    }

    @Override
    public String[] getBlocksAsJson(Peer peer, int count, int from) {
        // get blockchain parameter
        String path = String.format(URL_BLOCKS_FROM, count, from);
        String jsonBlocksStr = executeRequest(peer, path, String.class);

        // Parse only array content, but deserialize array item
        JsonArrayParser parser = new JsonArrayParser();
        return parser.getValuesAsArray(jsonBlocksStr);
    }

    /**
     * Retrieve the current block (with short cache)
     *
     * @return
     */
    public BlockchainBlock getCurrentBlock(long currencyId, boolean useCache) {
        if (!useCache) {
            return getCurrentBlock(currencyId);
        } else {
            return mCurrentBlockCache.get(currencyId);
        }
    }

    @Override
    public BlockchainBlock getCurrentBlock(long currencyId) {
        // get blockchain parameter
        BlockchainBlock result = executeRequest(currencyId, URL_BLOCK_CURRENT, BlockchainBlock.class);
        return result;
    }

    @Override
    public BlockchainBlock getCurrentBlock(Peer peer) {
        // get blockchain parameter
        BlockchainBlock result = executeRequest(peer, URL_BLOCK_CURRENT, BlockchainBlock.class);
        return result;
    }

    @Override
    public org.duniter.core.client.model.local.Currency getCurrencyFromPeer(Peer peer) {
        BlockchainParameters parameter = getParameters(peer);
        BlockchainBlock firstBlock = getBlock(peer, 0);
        BlockchainBlock lastBlock = getCurrentBlock(peer);

        org.duniter.core.client.model.local.Currency result = new org.duniter.core.client.model.local.Currency();
        result.setCurrencyName(parameter.getCurrency());
        result.setFirstBlockSignature(firstBlock.getSignature());
        result.setMembersCount(lastBlock.getMembersCount());
        result.setLastUD(parameter.getUd0());

        return result;
    }

    @Override
    public BlockchainParameters getBlockchainParametersFromPeer(Peer peer){
        return getParameters(peer);
    }

    @Override
    public long getLastUD(long currencyId) {
        // get block number with UD
        String blocksWithUdResponse = executeRequest(currencyId, URL_BLOCK_WITH_UD, String.class);
        Integer blockNumber = getLastBlockNumberFromJson(blocksWithUdResponse);

        // If no result (this could happen when no UD has been send
        if (blockNumber == null) {
            // get the first UD from currency parameter
            BlockchainParameters parameter = getParameters(currencyId);
            return parameter.getUd0();
        }

        // Get the UD from the last block with UD
        Long lastUD = getBlockDividend(currencyId, blockNumber);

        // Check not null (should never append)
        if (lastUD == null) {
            throw new TechnicalException("Unable to get last UD from server");
        }
        return lastUD.longValue();
    }

    @Override
    public long getLastUD(Peer peer) {
        // get block number with UD
        String blocksWithUdResponse = executeRequest(peer, URL_BLOCK_WITH_UD, String.class);
        Integer blockNumber = getLastBlockNumberFromJson(blocksWithUdResponse);

        // If no result (this could happen when no UD has been send
        if (blockNumber == null) {
            // get the first UD from currency parameter
            BlockchainParameters parameter = getParameters(peer);
            return parameter.getUd0();
        }

        // Get the UD from the last block with UD
        String path = String.format(URL_BLOCK, blockNumber);
        String json = executeRequest(peer, path, String.class);
        Long lastUD = getDividendFromBlockJson(json);

        // Check not null (should never append)
        if (lastUD == null) {
            throw new TechnicalException("Unable to get last UD from server");
        }
        return lastUD.longValue();

    }

    /**
     * Check is a identity is not already used by a existing member
     *
     * @param peer
     * @param identity
     * @throws UidAlreadyUsedException    if UID already used by another member
     * @throws PubkeyAlreadyUsedException if pubkey already used by another member
     */
    public void checkNotMemberIdentity(Peer peer, Identity identity) throws UidAlreadyUsedException, PubkeyAlreadyUsedException {
        ObjectUtils.checkNotNull(peer);
        ObjectUtils.checkNotNull(identity);
        ObjectUtils.checkArgument(StringUtils.isNotBlank(identity.getUid()));
        ObjectUtils.checkArgument(StringUtils.isNotBlank(identity.getPubkey()));

        // Read membership data from the UID
        BlockchainMemberships result = getMembershipByPubkeyOrUid(peer, identity.getUid());

        // uid already used by another member
        if (result != null) {
            throw new UidAlreadyUsedException(String.format("User identifier '%s' is already used by another member", identity.getUid()));
        }

        result = getMembershipByPubkeyOrUid(peer, identity.getPubkey());

        // pubkey already used by another member
        if (result != null) {
            throw new PubkeyAlreadyUsedException(String.format("Pubkey key '%s' is already used by another member", identity.getPubkey()));
        }
    }

    /**
     * Check is a wallet is a member, and load its attribute isMember and certTimestamp
     *
     * @param wallet
     * @throws UidMatchAnotherPubkeyException is uid already used by another pubkey
     */
    public void loadAndCheckMembership(Peer peer, Wallet wallet) throws UidMatchAnotherPubkeyException {
        ObjectUtils.checkNotNull(wallet);

        // Load membership data
        loadMembership(null, peer, wallet.getIdentity(), true);

        // Something wrong on pubkey : uid already used by another pubkey !
        if (wallet.getIdentity().getIsMember() == null) {
            throw new UidMatchAnotherPubkeyException(wallet.getPubKeyHash());
        }
    }

    /**
     * Load identity attribute isMember and timestamp
     *
     * @param identity
     */
    public void loadMembership(long currencyId, Identity identity, boolean checkLookupForNonMember) {
        loadMembership(currencyId, null, identity, checkLookupForNonMember);
    }


    public BlockchainMemberships getMembershipByUid(long currencyId, String uid) {
        ObjectUtils.checkArgument(StringUtils.isNotBlank(uid));

        BlockchainMemberships result = getMembershipByPubkeyOrUid(currencyId, uid);
        if (result == null || !uid.equals(result.getUid())) {
            return null;
        }
        return result;
    }

    public BlockchainMemberships getMembershipByPublicKey(long currencyId, String pubkey) {
        ObjectUtils.checkArgument(StringUtils.isNotBlank(pubkey));

        BlockchainMemberships result = getMembershipByPubkeyOrUid(currencyId, pubkey);
        if (result == null || !pubkey.equals(result.getPubkey())) {
            return null;
        }
        return result;
    }

    /**
     * Request to integrate the wot
     */
    public void requestMembership(Wallet wallet) {
        ObjectUtils.checkNotNull(wallet);
        ObjectUtils.checkNotNull(wallet.getCurrencyId());
        ObjectUtils.checkNotNull(wallet.getCertTimestamp());

        BlockchainBlock block = getCurrentBlock(wallet.getCurrencyId());

        // Compute membership document
        String membership = getMembership(wallet,
                block,
                true /*side in*/);

        if (log.isDebugEnabled()) {
            log.debug(String.format(
                    "Will send membership document: \n------\n%s------",
                    membership));
        }

        List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
        urlParameters.add(new BasicNameValuePair("membership", membership));

        HttpPost httpPost = new HttpPost(getPath(wallet.getCurrencyId(), URL_MEMBERSHIP));
        try {
            httpPost.setEntity(new UrlEncodedFormEntity(urlParameters));
        } catch (UnsupportedEncodingException e) {
            throw new TechnicalException(e);
        }

        String membershipResult = executeRequest(httpPost, String.class);
        if (log.isDebugEnabled()) {
            log.debug("received from /tx/process: " + membershipResult);
        }

        executeRequest(httpPost, String.class);
    }


    public void requestMembership(Peer peer, String currency, byte[] pubKey, byte[] secKey, String uid, String membershipBlockUid, String selfBlockUid) {
        // http post /blockchain/membership
        HttpPost httpPost = new HttpPost(getPath(peer, URL_MEMBERSHIP));

        // compute the self-certification
        String membership = getSignedMembership(currency, pubKey, secKey, uid, membershipBlockUid, selfBlockUid, true/*side in*/);

        List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
        urlParameters.add(new BasicNameValuePair("membership", membership));

        try {
            httpPost.setEntity(new UrlEncodedFormEntity(urlParameters));
        }
        catch(UnsupportedEncodingException e) {
            throw new TechnicalException(e);
        }

        // Execute the request
        executeRequest(httpPost, String.class);
    }

    public BlockchainMemberships getMembershipByPubkeyOrUid(long currencyId, String uidOrPubkey) {
        String path = String.format(URL_MEMBERSHIP_SEARCH, uidOrPubkey);

        // search blockchain membership
        try {
            return executeRequest(currencyId, path, BlockchainMemberships.class);
        } catch (HttpBadRequestException e) {
            log.debug("No member matching this pubkey or uid: " + uidOrPubkey);
            return null;
        }
    }

    public BlockchainMemberships getMembershipByPubkeyOrUid(Peer peer, String uidOrPubkey) {
        String path = String.format(URL_MEMBERSHIP_SEARCH, uidOrPubkey);

        // search blockchain membership
        try {
            BlockchainMemberships result = executeRequest(peer, path, BlockchainMemberships.class);
            return result;
        } catch (HttpBadRequestException e) {
            log.debug("No member matching this pubkey or uid: " + uidOrPubkey);
            return null;
        }
    }

    public String getMembership(Wallet wallet,
                                BlockchainBlock block,
                                boolean sideIn
    ) {

        // Create the member ship document
        String membership = getUnsignedMembership( wallet.getCurrency(),
                wallet.getPubKeyHash(),
                wallet.getUid(),
                block.getNumber() + '-' + block.getHash(),
                wallet.getCertTimestamp(),
                sideIn
        );

        // Add signature
        CryptoService cryptoService = ServiceLocator.instance().getCryptoService();
        String signature = cryptoService.sign(membership, wallet.getSecKey());

        return new StringBuilder().append(membership).append(signature)
                .append('\n').toString();
    }

    /**
     * Get UD, by block number
     *
     * @param currencyId
     * @param startOffset
     * @return
     */
    public Map<Integer, Long> getUDs(long currencyId, long startOffset) {
        log.debug(String.format("Getting block's UD from block [%s]", startOffset));

        int[] blockNumbersWithUD = getBlocksWithUD(currencyId);

        Map<Integer, Long> result = new LinkedHashMap<Integer,Long>();

//         Insert the UD0 (if need)
//        if (startOffset <= 0) {
//            BlockchainParameters parameters = getParameters(currencyId, true/*with cache*/);
//            result.put(0, parameters.getUd0());
//        }

        boolean previousBlockInsert = false;
        if (blockNumbersWithUD != null && blockNumbersWithUD.length != 0) {
            Integer previousBlockNumberWithUd = null;
            for (Integer blockNumber : blockNumbersWithUD) {
                if (blockNumber >= startOffset) {
                    if(!previousBlockInsert){
                        Long previousUd = getParameters(currencyId, true/*with cache*/).getUd0();
                        Integer previousBlockNumber = 0;
                        if(previousBlockNumberWithUd!=null){
                            previousUd = getBlockDividend(currencyId, previousBlockNumberWithUd);
                            if (previousUd == null) {
                                throw new TechnicalException(
                                        String.format("Unable to get UD from server block [%s]",
                                                previousBlockNumberWithUd)
                                );
                            }
                            previousBlockNumber = previousBlockNumberWithUd;
                        }
                        result.put(previousBlockNumber, previousUd);
                        previousBlockInsert = true;
                    }
                    Long ud = getBlockDividend(currencyId, blockNumber);
                    // Check not null (should never append)
                    if (ud == null) {
                        throw new TechnicalException(String.format("Unable to get UD from server block [%s]", blockNumber));
                    }
                    result.put(blockNumber, ud);
                }else{
                    previousBlockNumberWithUd = blockNumber;
                }
            }
        }else{
            result.put(0, getParameters(currencyId, true/*with cache*/).getUd0());
        }

        return result;
    }

    @Override
    public void addNewBlockListener(long currencyId, WebsocketClientEndpoint.MessageHandler messageHandler) {
        Peer peer = peerService.getActivePeerByCurrencyId(currencyId);
        addNewBlockListener(peer, messageHandler);
    }

    @Override
    public void addNewBlockListener(Peer peer, WebsocketClientEndpoint.MessageHandler messageHandler) {

        try {
            URI wsBlockURI = new URI(String.format("ws://%s:%s/ws/block",
                    peer.getHost(),
                    peer.getPort()));

            log.info(String.format("Starting to listen block from [%s]...", wsBlockURI.toString()));

            // Get the websocket, or open new one if not exists
            WebsocketClientEndpoint wsClientEndPoint = blockWsEndPoints.get(wsBlockURI);
            if (wsClientEndPoint == null || wsClientEndPoint.isClosed()) {
                wsClientEndPoint = new WebsocketClientEndpoint(wsBlockURI);
                blockWsEndPoints.put(wsBlockURI, wsClientEndPoint);
            }

            // add listener
            wsClientEndPoint.addMessageHandler(messageHandler);

        } catch (URISyntaxException | ServiceConfigurationError ex) {
            throw new TechnicalException("could not create URI need for web socket on block: " + ex.getMessage());
        }

    }

    /* -- Internal methods -- */

    /**
     * Initialize caches
     */
    protected void initCaches() {
        int cacheTimeInMillis = config.getNetworkCacheTimeInMillis();

        mCurrentBlockCache = new SimpleCache<Long, BlockchainBlock>(cacheTimeInMillis) {
            @Override
            public BlockchainBlock load(Long currencyId) {
                return getCurrentBlock(currencyId);
            }
        };

        mParametersCache = new SimpleCache<Long, BlockchainParameters>(/*eternal cache*/) {
            @Override
            public BlockchainParameters load(Long currencyId) {
                return getParameters(currencyId);
            }
        };
    }


    protected void loadMembership(Long currencyId, Peer peer, Identity identity, boolean checkLookupForNonMember) {
        ObjectUtils.checkNotNull(identity);
        ObjectUtils.checkArgument(StringUtils.isNotBlank(identity.getUid()));
        ObjectUtils.checkArgument(StringUtils.isNotBlank(identity.getPubkey()));
        ObjectUtils.checkArgument(peer != null || currencyId != null);

        // Read membership data from the UID
        BlockchainMemberships result = peer != null
                ? getMembershipByPubkeyOrUid(peer, identity.getUid())
                : getMembershipByPubkeyOrUid(currencyId, identity.getUid());

        // uid not used = not was member
        if (result == null) {
            identity.setMember(false);

            if (checkLookupForNonMember) {
                WotRemoteService wotService = ServiceLocator.instance().getWotRemoteService();
                Identity lookupIdentity = peer != null
                        ? wotService.getIdentity(peer, identity.getUid(), identity.getPubkey())
                        : wotService.getIdentity(currencyId, identity.getUid(), identity.getPubkey());

                // Self certification exists, update the cert timestamp
                if (lookupIdentity != null) {
                    identity.setTimestamp(lookupIdentity.getTimestamp());
                }

                // Self certification not exists: make sure the cert time is cleaning
                else {
                    identity.setTimestamp(null);
                }
            }
        }

        // UID and pubkey is a member: fine
        else if (identity.getPubkey().equals(result.getPubkey())) {
            identity.setMember(true);
            //FIXME identity.setTimestamp(result.getSigDate());
        }

        // Something wrong on pubkey : uid already used by anither pubkey !
        else {
            identity.setMember(null);
        }

    }

    private int[] getBlocksWithUD(long currencyId) {
        log.debug("Getting blocks with UD");

        String json = executeRequest(currencyId, URL_BLOCK_WITH_UD, String.class);



        int startIndex = json.indexOf("[");
        int endIndex = json.lastIndexOf(']');

        if (startIndex == -1 || endIndex == -1) {
            return null;
        }

        String blockNumbersStr = json.substring(startIndex + 1, endIndex).trim();

        if (StringUtils.isBlank(blockNumbersStr)) {
            return null;
        }


        String[] blockNumbers = blockNumbersStr.split(",");
        int[] result = new int[blockNumbers.length];
        try {
            int i=0;
            for (String blockNumber : blockNumbers) {
                result[i++] = Integer.parseInt(blockNumber.trim());
            }
        }
        catch(NumberFormatException e){
            if (log.isDebugEnabled()) {
                log.debug(String.format("Bad format of the response '%s'.", URL_BLOCK_WITH_UD));
            }
            throw new TechnicalException("Unable to read block with UD numbers: " + e.getMessage(), e);
        }

        return result;
    }

    protected String getSignedMembership(String currency,
                                      byte[] pubKey,
                                      byte[] secKey,
                                      String userId,
                                      String membershipBlockUid,
                                      String selfBlockUid,
                                      boolean sideIn) {
        // Compute the pub key hash
        String pubKeyHash = CryptoUtils.encodeBase58(pubKey);

        // Create the member ship document
        String membership = getUnsignedMembership(currency,
                pubKeyHash,
                userId,
                membershipBlockUid,
                selfBlockUid,
                sideIn
        );

        // Add signature
        CryptoService cryptoService = ServiceLocator.instance().getCryptoService();
        String signature = cryptoService.sign(membership, secKey);

        return new StringBuilder().append(membership).append(signature)
                .append('\n').toString();
    }

    protected String getUnsignedMembership(String currency,
                                           String pubkey,
                                           String userId,
                                           String membershipBlockUid,
                                           String selfBlockUid,
                                           boolean sideIn
    ) {
        // see https://github.com/ucoin-io/ucoin/blob/master/doc/Protocol.md#membership
        return new StringBuilder()
                .append("Version: ").append(Protocol.VERSION)
                .append("\nType: ").append(Protocol.TYPE_MEMBERSHIP)
                .append("\nCurrency: ").append(currency)
                .append("\nIssuer: ").append(pubkey)
                .append("\nBlock: ").append(membershipBlockUid)
                .append("\nMembership: ").append(sideIn ? "IN" : "OUT")
                .append("\nUserID: ").append(userId)
                .append("\nCertTS: ").append(selfBlockUid)
                .append("\n")
                .toString();
    }

    private Integer getLastBlockNumberFromJson(final String json) {

        int startIndex = json.lastIndexOf(',');
        int endIndex = json.lastIndexOf(']');
        if (startIndex == -1 || endIndex == -1) {
            return null;
        }

        String blockNumberStr = json.substring(startIndex+1,endIndex).trim();
        try {
            return Integer.parseInt(blockNumberStr);
        } catch(NumberFormatException e) {
            if (log.isDebugEnabled()) {
                log.debug("Could not parse JSON (block numbers)");
            }
            throw new TechnicalException("Could not parse server response");
        }
    }


    protected Long getDividendFromBlockJson(String blockJson) {

        int startIndex = blockJson.indexOf(JSON_DIVIDEND_ATTR);
        if (startIndex == -1) {
            return null;
        }
        startIndex += JSON_DIVIDEND_ATTR.length();
        int endIndex = blockJson.indexOf(',', startIndex);
        if (endIndex == -1) {
            return null;
        }

        String dividendStr = blockJson.substring(startIndex, endIndex).trim();
        if (dividendStr.length() == 0
                || "null".equals(dividendStr)) {
            return null;
        }

        return Long.parseLong(dividendStr);
    }

}
