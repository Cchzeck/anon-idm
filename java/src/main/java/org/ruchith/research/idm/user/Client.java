package org.ruchith.research.idm.user;

import it.unisa.dia.gas.jpbc.Element;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Set;

import org.bouncycastle.util.encoders.Base64;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.node.ArrayNode;
import org.codehaus.jackson.node.ObjectNode;
import org.ruchith.ae.base.AECipherTextBlock;
import org.ruchith.ae.base.AEParameters;
import org.ruchith.ae.base.AEPrivateKey;
import org.ruchith.ae.base.ContactKeyGen;
import org.ruchith.ae.base.Decrypt;
import org.ruchith.research.idm.IdentityClaim;
import org.ruchith.research.idm.IdentityClaimDefinition;

public class Client {

	private ClaimWallet wallet;

	private Hashtable<String, AEPrivateKey> privKeys = new Hashtable<String, AEPrivateKey>();

	public Client(String walletDir) throws Exception {
		this.wallet = ClaimWallet.getInstance(walletDir);
	}
	
	public String getClaimdefNameJson() throws Exception {
		Set<String> set = wallet.getClaimdefNameSet();
		ObjectMapper mapper = new ObjectMapper();
		String res = mapper.writeValueAsString(set);
		
		return res;
	}
	
	public String getPublicParams(String claimDefName) throws Exception {
		String claimdef = wallet.getClaim(claimDefName).getDefinition().serializeJSON().toString();
		
		ObjectMapper mapper = new ObjectMapper();
		JsonNode root_node = mapper.readTree(claimdef);
		String params = root_node.get("params").toString();
		System.out.println("params: " + params);
		return params;
	}
	
	public String getRandomIDclaim(String claimDefName) {
		byte[] tmp = wallet.getClaim(claimDefName).getClaimKey().toBytes();
		String result = new String(Base64.encode(tmp));
		System.out.println("test: " + result);
		return result;
	}
	
	public void updateClaim(String claimDefName, String param, String c, String dgstStr, String sigStr, String certStr) throws Exception {
		IdentityClaim tmpClaim = wallet.getClaim(claimDefName);
		
		// ClaimDefinition creation
		CertificateFactory factory = CertificateFactory.getInstance("X.509");
		Certificate tmpCert = factory.generateCertificate(new ByteArrayInputStream(Base64.decode(certStr)));
		
		MessageDigest dgst = MessageDigest.getInstance("SHA-512");
		Signature sig = Signature.getInstance("SHA512withRSA");
		
		ObjectMapper mapper = new ObjectMapper();
		ObjectNode on = (ObjectNode) mapper.readTree(param);
		AEParameters aeParams = new AEParameters(on);
		
		IdentityClaimDefinition tmpDef = new IdentityClaimDefinition(claimDefName, aeParams);
		
		// Verify dgst
		byte[] contentBytes = tmpDef.getDgstContet().getBytes();
		
		dgst.reset();
		dgst.update(contentBytes);
		String newDgstVal = new String(Base64.encode(dgst.digest()));
		
		if (!newDgstVal.equals(dgstStr)) {
			System.out.println("Hash doesn't match!");
			return;
		}
		else {
			System.out.println("Hash matches!");
		}
		
		// Verify signature
		sig.initVerify(tmpCert);
		sig.update(contentBytes);
		if (!sig.verify(Base64.decode(sigStr))) {
			System.out.println("Incorrect Signature!");
			return;
		}
		else {
			System.out.println("Correct Signature!");
		}
		
		tmpDef.setB64Hash(dgstStr);
		tmpDef.setB64Sig(sigStr);
		
		tmpDef.setCert(tmpCert);		
		
		// New ClaimDefinition set
		IdentityClaim newClaim = new IdentityClaim();
		newClaim.init(tmpDef);
		
		// updated key
		AEPrivateKey newAEClaim = tmpClaim.getClaim();
		Element tmpElem = aeParams.getPairing().getG1().newElement();
		tmpElem.setFromBytes(Base64.decode(c));
		newAEClaim.setC1(tmpElem.getImmutable());
		newClaim.setClaim(newAEClaim);
		
		newClaim.setClaimKey(tmpClaim.getClaimKey());
		
		wallet.updateClaim(newClaim);
	}

	public String generateRequest(String claimName) throws Exception {
		IdentityClaim claim = wallet.getClaim(claimName);

		IdentityClaimDefinition icd = claim.getDefinition();

		// Create request
		AEParameters params = icd.getParams();

		ContactKeyGen conKeyGen = new ContactKeyGen();
		conKeyGen.init(claim.getClaimKey(), claim.getClaim(), params);
		Element r = conKeyGen.genRandomID();
		AEPrivateKey tmpPriv = conKeyGen.getTmpPrivKey(r);

		this.privKeys.put(claimName, tmpPriv);

		Element val = conKeyGen.getTmpPubKey(r);

		// System.out.println(val);

		byte[] bytes = val.toBytes();
		return new String(Base64.encode(bytes));
	}

	public String generateRequestGivenR(String claimName, Element r) throws Exception {
		IdentityClaim claim = wallet.getClaim(claimName);
		// System.out.println("Creating priv key of : " + claimName);
		IdentityClaimDefinition icd = claim.getDefinition();

		// Create request
		AEParameters params = icd.getParams();

		ContactKeyGen conKeyGen = new ContactKeyGen();
		Element claimKey = claim.getClaimKey();
		// System.out.println(claimKey);
		conKeyGen.init(claimKey, claim.getClaim(), params);
		AEPrivateKey tmpPriv = conKeyGen.getTmpPrivKey(r);

		this.privKeys.put(claimName, tmpPriv);

		Element val = conKeyGen.getTmpPubKey(r);

		// System.out.println(val);
		byte[] bytes = val.toBytes();
		return new String(Base64.encode(bytes));
	}

	public String generateNRequests(int n) throws Exception {

		String retVal = "";
		for (int i = 0; i < n; i++) {
			String req = this.generateRequest("claim" + i);
			if (i > 0) {
				retVal += ",";
			}
			retVal += req;
		}
		return retVal;
	}

	public String generateANRequests(int n) throws Exception {

		IdentityClaim claim = wallet.getClaim("claim_a_0");

		IdentityClaimDefinition icd = claim.getDefinition();
		AEParameters params = icd.getParams();
		ContactKeyGen conKeyGen = new ContactKeyGen();
		conKeyGen.init(claim.getClaimKey(), claim.getClaim(), params);
		Element r = conKeyGen.genRandomID().getImmutable();

		String lastReq = null;
		for (int i = 0; i < n; i++) {
			// Use the same r
			lastReq = this.generateRequestGivenR("claim_a_" + i, r);
		}
		// System.out.println(lastReq);
		return lastReq;
	}

	public String generateANRequestsThreads(int n) throws Exception {

		IdentityClaim claim = wallet.getClaim("claim_a_0");

		IdentityClaimDefinition icd = claim.getDefinition();
		AEParameters params = icd.getParams();
		ContactKeyGen conKeyGen = new ContactKeyGen();
		conKeyGen.init(claim.getClaimKey(), claim.getClaim(), params);
		Element r = conKeyGen.genRandomID().getImmutable();

		String lastReq = this.generateRequestGivenR("claim_a_0", r);
		for (int i = 1; i < n; i++) {
			// Use the same r
			new PrivKeyCreatorThread("claim_a_" + i, r, this.privKeys).start();

		}
		// System.out.println(lastReq);
		return lastReq;
	}

	public String extractSessionKey(String claimName, String spResponse) throws Exception {

		ObjectMapper mapper = new ObjectMapper();
		ObjectNode ctOn = (ObjectNode) mapper.readTree(spResponse);

		IdentityClaim claim = this.wallet.getClaim(claimName);

		AEPrivateKey tmpPriv = this.privKeys.get(claimName);

		AEParameters params = claim.getDefinition().getParams();
		AECipherTextBlock ct = new AECipherTextBlock(ctOn, params.getPairing());

		Decrypt decrypt = new Decrypt();
		decrypt.init(params);
		Element result = decrypt.doDecrypt(ct, tmpPriv);

		String sk = new String(Base64.encode(result.toBytes()));
		sk = sk.replaceAll(" ", "");
		return sk;
	}

	public String extractSessionKeyDouble(String claimName1, String claimName2, String ch1, String ch2)
			throws Exception {

		ObjectMapper mapper = new ObjectMapper();
		ObjectNode ct1On = (ObjectNode) mapper.readTree(ch1);
		ObjectNode ct2On = (ObjectNode) mapper.readTree(ch2);

		IdentityClaim claim1 = this.wallet.getClaim(claimName1);
		AEPrivateKey tmpPriv1 = this.privKeys.get(claimName1);

		AEParameters params1 = claim1.getDefinition().getParams();
		AECipherTextBlock ct1 = new AECipherTextBlock(ct1On, params1.getPairing());

		Decrypt decrypt = new Decrypt();
		decrypt.init(params1);
		Element result1 = decrypt.doDecrypt(ct1, tmpPriv1);

		IdentityClaim claim2 = this.wallet.getClaim(claimName2);
		AEPrivateKey tmpPriv2 = this.privKeys.get(claimName2);

		AEParameters params2 = claim2.getDefinition().getParams();
		AECipherTextBlock ct2 = new AECipherTextBlock(ct2On, params2.getPairing());

		decrypt.init(params2);
		Element result2 = decrypt.doDecrypt(ct2, tmpPriv2);

		Element result = result1.add(result2);

		String sk = new String(Base64.encode(result.toBytes()));
		sk = sk.replaceAll(" ", "");
		return sk;
	}

	public String extractSessionKeyN(int n, String ch) throws Exception {

		ObjectMapper mapper = new ObjectMapper();
		ObjectNode cts = (ObjectNode) mapper.readTree(ch);

		Decrypt decrypt = new Decrypt();

		Element res = null;
		for (int i = 0; i < n; i++) {
			String claimName = "claim" + i;
			ObjectNode ctOn = (ObjectNode) cts.get(claimName);
			IdentityClaim claim = this.wallet.getClaim(claimName);
			AEPrivateKey tmpPriv = this.privKeys.get(claimName);

			AEParameters params = claim.getDefinition().getParams();
			AECipherTextBlock ct = new AECipherTextBlock(ctOn, params.getPairing());
			decrypt.init(params);
			Element tmpRes = decrypt.doDecrypt(ct, tmpPriv);
			// System.out.println("Part : " + i + " : " + tmpRes);
			if (res == null) {
				res = tmpRes;
			} else {
				res = res.add(tmpRes);
			}
		}
		// System.out.println("Final: " + res);
		String sk = new String(Base64.encode(res.toBytes()));
		sk = sk.replaceAll(" ", "");
		// System.out.println("Returned : "+ sk);
		return sk;
	}

	public String extractSessionKeyAN(int n, String ch) throws Exception {

		ObjectMapper mapper = new ObjectMapper();
		ObjectNode cts = (ObjectNode) mapper.readTree(ch);

		Decrypt decrypt = new Decrypt();

		Element res = null;
		for (int i = 0; i < n; i++) {
			String claimName = "claim_a_" + i;
			ObjectNode ctOn = (ObjectNode) cts.get(claimName);
			IdentityClaim claim = this.wallet.getClaim(claimName);
			AEPrivateKey tmpPriv = this.privKeys.get(claimName);

			AEParameters params = claim.getDefinition().getParams();
			AECipherTextBlock ct = new AECipherTextBlock(ctOn, params.getPairing());
			decrypt.init(params);
			Element tmpRes = decrypt.doDecrypt(ct, tmpPriv);
			// System.out.println("Part : " + i + " : " + tmpRes);
			if (res == null) {
				res = tmpRes;
			} else {
				res = res.add(tmpRes);
			}
		}
		// System.out.println("Final: " + res);
		String sk = new String(Base64.encode(res.toBytes()));
		sk = sk.replaceAll(" ", "");
		// System.out.println("Returned : "+ sk);
		return sk;
	}

	public String extractSessionKeyNThreads(int n, String ch) throws Exception {

		ObjectMapper mapper = new ObjectMapper();
		ObjectNode cts = (ObjectNode) mapper.readTree(ch);

		ArrayList<DecrypterThread> dts = new ArrayList<Client.DecrypterThread>();
		// HashMap<String, Element> results = new HashMap<String, Element>();

		Element res = this.wallet.getClaim("claim0").getDefinition().getParams().getPairing().getGT().newOneElement();
		for (int i = 0; i < n; i++) {
			String claimName = "claim" + i;
			ObjectNode ctOn = (ObjectNode) cts.get(claimName);
			IdentityClaim claim = this.wallet.getClaim(claimName);
			AEPrivateKey tmpPriv = this.privKeys.get(claimName);

			AEParameters params = claim.getDefinition().getParams();
			AECipherTextBlock ct = new AECipherTextBlock(ctOn, params.getPairing());

			DecrypterThread dt = new DecrypterThread(claimName, params, ct, tmpPriv, res);
			dt.start();
			dts.add(dt);
		}

		for (DecrypterThread dt : dts) {
			dt.join();
		}

		// Collection<Element> values = results.values();
		// Element res = null;
		// for (Iterator iterator = values.iterator(); iterator.hasNext();) {
		// System.out.println();
		// Element element = (Element) iterator.next();
		// if(res == null) {
		// res = element;
		// } else {
		// res = res.add(element);
		// }
		// }

		// System.out.println("Final: " + res);
		String sk = new String(Base64.encode(res.toBytes()));
		sk = sk.replaceAll(" ", "");
		// System.out.println("Returned : "+ sk);
		return sk;
	}

	class DecrypterThread extends Thread {

		private AEParameters params;
		private AECipherTextBlock ct;
		private AEPrivateKey tmpPriv;
		// private HashMap<String, Element> results;
		private Element res;
		private String claimName;

		// public DecrypterThread(String claimName, AEParameters params, AECipherTextBlock ct, AEPrivateKey tmpPriv,
		// HashMap<String, Element> res) {
		public DecrypterThread(String claimName, AEParameters params, AECipherTextBlock ct, AEPrivateKey tmpPriv,
				Element res) {
			this.params = params;
			this.ct = ct;
			this.tmpPriv = tmpPriv;
			// this.results = res;
			this.res = res;
			this.claimName = claimName;
		}

		public void run() {
			Decrypt d = new Decrypt();
			d.init(this.params);
			Element tmpRes = d.doDecrypt(this.ct, this.tmpPriv);
			// this.results.put(this.claimName, tmpRes);
			this.res.add(tmpRes);
		}

	}

	public String extractSessionKeyNThreadsOneReq(int n, String ch) throws Exception {

		ObjectMapper mapper = new ObjectMapper();
		ObjectNode cts = (ObjectNode) mapper.readTree(ch);

		ArrayList<DecrypterThread2> dts = new ArrayList<Client.DecrypterThread2>();
		HashMap<String, Element> results = new HashMap<String, Element>();

		// Element res =
		// this.wallet.getClaim("claim_a_0").getDefinition().getParams().getPairing().getGT().newZeroElement();
		for (int i = 0; i < n; i++) {
			String claimName = "claim_a_" + i;
			ObjectNode ctOn = (ObjectNode) cts.get(claimName);
			IdentityClaim claim = this.wallet.getClaim(claimName);
			AEPrivateKey tmpPriv = this.privKeys.get(claimName);

			AEParameters params = claim.getDefinition().getParams();
			AECipherTextBlock ct = new AECipherTextBlock(ctOn, params.getPairing());

			DecrypterThread2 dt = new DecrypterThread2(claimName, params, ct, tmpPriv, results);
			dt.start();
			dts.add(dt);
		}

		for (DecrypterThread2 dt : dts) {
			dt.join();
		}

		Collection<Element> values = results.values();
		Element res = null;
		for (Iterator iterator = values.iterator(); iterator.hasNext();) {
			Element element = (Element) iterator.next();
			if (res == null) {
				res = element;
			} else {
				res = res.add(element);
			}
		}

		// System.out.println("Final: " + res);
		System.out.println(res);
		String sk = new String(Base64.encode(res.toBytes()));
		sk = sk.replaceAll(" ", "");
		// System.out.println("Returned : "+ sk);
		return sk;
	}

	class DecrypterThread2 extends Thread {

		private AEParameters params;
		private AECipherTextBlock ct;
		private AEPrivateKey tmpPriv;
		private HashMap<String, Element> results;
		private String claimName;

		public DecrypterThread2(String claimName, AEParameters params, AECipherTextBlock ct, AEPrivateKey tmpPriv,
				HashMap<String, Element> res) {
			this.params = params;
			this.ct = ct;
			this.tmpPriv = tmpPriv;
			this.results = res;
			this.claimName = claimName;
		}

		public void run() {
			Decrypt d = new Decrypt();
			d.init(this.params);
			Element tmpRes = d.doDecrypt(this.ct, this.tmpPriv);
			this.results.put(this.claimName, tmpRes);
		}

	}

	class PrivKeyCreatorThread extends Thread {

		private String claimName;
		private Element r;
		private Hashtable<String, AEPrivateKey> keys;

		public PrivKeyCreatorThread(String claimName, Element r, Hashtable<String, AEPrivateKey> keys) {
			this.claimName = claimName;
			this.r = r;
			this.keys = keys;
		}

		public void run() {
			IdentityClaim claim = wallet.getClaim(claimName);
			// System.out.println("Creating priv key of : " + claimName);
			IdentityClaimDefinition icd = claim.getDefinition();

			// Create request
			AEParameters params = icd.getParams();

			ContactKeyGen conKeyGen = new ContactKeyGen();
			Element claimKey = claim.getClaimKey();
			// System.out.println(claimKey);
			conKeyGen.init(claimKey, claim.getClaim(), params);
			AEPrivateKey tmpPriv = conKeyGen.getTmpPrivKey(r);

			this.keys.put(claimName, tmpPriv);
		}

	}

}
