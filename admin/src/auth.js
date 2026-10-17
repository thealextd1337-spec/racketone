import { createSupportError, SupportError } from "./domain.js";

function asSafeAuthError(error) {
  if (error instanceof SupportError) return error;
  const code = error?.code === "mfa_required" ? "support_mfa_required" : "auth_failed";
  return new SupportError(code);
}

function totpFactors(data) {
  return Array.isArray(data?.totp) ? data.totp : [];
}

function safeQrDataUrl(value) {
  return typeof value === "string"
    && value.length < 500000
    && /^data:image\/svg\+xml(?:;charset=utf-8|;utf-8|;base64)?,/i.test(value);
}

export function createAuthService(client) {
  let recoveryUserId = null;

  async function requestRecovery(emailValue) {
    try {
      const { error } = await client.auth.resetPasswordForEmail(String(emailValue ?? "").trim());
      if (error) throw error;
      return {};
    } catch (error) {
      throw asSafeAuthError(error);
    }
  }

  async function verifyRecovery(emailValue, codeValue) {
    recoveryUserId = null;
    let sessionCreated = false;
    try {
      const token = String(codeValue ?? "").replace(/\s/g, "");
      if (!/^\d{6,8}$/.test(token)) throw new SupportError("auth_failed");
      const { data, error } = await client.auth.verifyOtp({
        email: String(emailValue ?? "").trim(), token, type: "recovery",
      });
      sessionCreated = Boolean(data?.session);
      if (error) throw error;
      if (!data?.session || !data?.user?.id) throw new SupportError("auth_failed");
      const verified = (await factors()).find((factor) => factor.status === "verified");
      recoveryUserId = data.user.id;
      return verified
        ? { kind: "challenge", factorId: verified.id }
        : { kind: "password" };
    } catch (error) {
      if (sessionCreated) await signOut();
      throw asSafeAuthError(error);
    }
  }

  async function updateRecoveredPassword(password) {
    try {
      if (!recoveryUserId || typeof password !== "string" || password.length < 12) {
        throw new SupportError("auth_failed");
      }
      const { data, error } = await client.auth.getUser();
      if (error) throw error;
      if (data?.user?.id !== recoveryUserId) throw new SupportError("auth_failed");
      const available = await factors();
      if (available.some((factor) => factor.status === "verified") && await assurance() !== "aal2") {
        throw new SupportError("support_mfa_required");
      }
      const { error: updateError } = await client.auth.updateUser({ password });
      if (updateError) throw updateError;
      recoveryUserId = null;
      return {};
    } catch (error) {
      throw asSafeAuthError(error);
    }
  }
  async function assurance() {
    const { data, error } = await client.auth.mfa.getAuthenticatorAssuranceLevel();
    if (error) throw asSafeAuthError(error);
    return data?.currentLevel === "aal2" ? "aal2" : "aal1";
  }

  async function factors() {
    const { data, error } = await client.auth.mfa.listFactors();
    if (error) throw asSafeAuthError(error);
    return totpFactors(data);
  }

  async function signIn(emailValue, password) {
    try {
      const email = String(emailValue ?? "").trim();
      const { data, error } = await client.auth.signInWithPassword({ email, password });
      if (error) throw asSafeAuthError(error);
      if (!data?.session || !data?.user) throw new SupportError("auth_failed");
      const currentAssurance = await assurance();
      const available = await factors();
      const verified = available.find((factor) => factor.status === "verified");

      if (verified && currentAssurance === "aal2") {
        return { kind: "unlocked", email: data.user.email ?? email, aal: "aal2" };
      }
      if (verified) {
        return { kind: "challenge", email: data.user.email ?? email, factorId: verified.id };
      }

      const pending = available.filter((factor) => factor.status !== "verified");
      for (const factor of pending) {
        const { error: removeError } = await client.auth.mfa.unenroll({ factorId: factor.id });
        if (removeError) throw asSafeAuthError(removeError);
      }
      const { data: enrolled, error: enrollError } = await client.auth.mfa.enroll({
        factorType: "totp",
        friendlyName: "Advantly support",
      });
      if (enrollError) throw asSafeAuthError(enrollError);
      if (!enrolled?.id || !safeQrDataUrl(enrolled?.totp?.qr_code) || !enrolled?.totp?.secret) {
        throw new SupportError("auth_failed");
      }
      return {
        kind: "setup",
        email: data.user.email ?? email,
        factorId: enrolled.id,
        qrCode: enrolled.totp.qr_code,
        secret: enrolled.totp.secret,
      };
    } catch (error) {
      throw asSafeAuthError(error);
    }
  }

  async function verifyTotp(factorId, codeValue) {
    try {
      const code = String(codeValue ?? "").replace(/\s/g, "");
      if (!factorId || !/^\d{6,8}$/.test(code)) throw new SupportError("invalid-totp");
      const { error } = await client.auth.mfa.challengeAndVerify({ factorId, code });
      if (error) throw asSafeAuthError(error);
      const [currentAssurance, available] = await Promise.all([assurance(), factors()]);
      if (currentAssurance !== "aal2" || !available.some((factor) => factor.id === factorId && factor.status === "verified")) {
        throw new SupportError("support_mfa_required");
      }
      return { aal: "aal2" };
    } catch (error) {
      throw asSafeAuthError(error);
    }
  }

  async function signOut() {
    recoveryUserId = null;
    try {
      const { error } = await client.auth.signOut();
      if (error) throw createSupportError(error);
    } catch {
      // Der Aufrufer leert die Oberfläche auch bei fehlgeschlagener Server-Abmeldung.
    }
  }

  function onAuthStateChange(callback) {
    const { data } = client.auth.onAuthStateChange((event, session) => {
      if (event === "SIGNED_OUT" || (event === "TOKEN_REFRESHED" && !session)) {
        recoveryUserId = null;
        callback(event);
      }
    });
    return () => data?.subscription?.unsubscribe?.();
  }

  return Object.freeze({ signIn, verifyTotp, signOut, onAuthStateChange, requestRecovery, verifyRecovery, updateRecoveredPassword });
}
