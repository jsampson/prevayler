package org.prevayler.demos.demo2.business.transactions;

import org.prevayler.demos.demo2.business.Account;

public class Deposit extends AccountTransaction {

	private final long _amount;

	public Deposit(Account account, long amount) {
		super(account);
		_amount = amount;
	}

	public void executeAndQuery(Account account) throws Account.InvalidAmount {
		account.deposit(_amount);
	}
}