package io.ucoin.ucoinj.core.client.service.bma;

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


import io.ucoin.ucoinj.core.beans.Service;
import io.ucoin.ucoinj.core.client.model.bma.TxHistory;
import io.ucoin.ucoinj.core.client.model.bma.TxSource;
import io.ucoin.ucoinj.core.client.model.local.Peer;
import io.ucoin.ucoinj.core.client.model.local.Wallet;
import io.ucoin.ucoinj.core.client.service.exception.InsufficientCreditException;


public interface TransactionRemoteService extends Service {

	String transfert(Wallet wallet, String destPubKey, long amount,
							String comment) throws InsufficientCreditException;

	TxSource getSources(long currencyId, String pubKey);

    TxSource getSources(Peer peer, String pubKey);

    long getCreditOrZero(long currencyId, String pubKey);

    Long getCredit(long currencyId, String pubKey);

    Long getCredit(Peer peer, String pubKey);

    long computeCredit(TxSource.Source[] sources);

    TxHistory getTxHistory(long currencyId, String pubKey, long fromBlockNumber, long toBlockNumber);
}
