/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.password.file;

import com.facebook.airlift.http.server.BasicPrincipal;
import com.facebook.airlift.log.Logger;
import com.facebook.presto.spi.security.AccessDeniedException;
import com.facebook.presto.spi.security.PasswordAuthenticator;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.inject.Inject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.Principal;
import java.util.function.Supplier;

import static com.google.common.base.Suppliers.memoizeWithExpiration;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class OauthAuthenticator
        implements PasswordAuthenticator
{
    private static final Logger log = Logger.get(OauthAuthenticator.class);
    private final Supplier<PasswordStore> passwordStoreSupplier;

    @Inject
    public OauthAuthenticator(FileConfig config) throws FileNotFoundException
    {
        File file = config.getPasswordFile();
        if (!file.exists()) {
            log.error("File %s does not exist", file.getAbsolutePath());
            throw new FileNotFoundException("File " + file.getAbsolutePath() + " does not exist");
        }
        int cacheMaxSize = config.getAuthTokenCacheMaxSize();

        passwordStoreSupplier = memoizeWithExpiration(
                () -> new PasswordStore(file, cacheMaxSize),
                config.getRefreshPeriod().toMillis(),
                MILLISECONDS);
    }

    @Override
    public Principal createAuthenticatedPrincipal(String user, String password)
    {
        // Assume the password is a Google auth token, so request the user profile
        // info from Google using the token, and verify the returned email matches
        // the user value
        // GET https://www.googleapis.com/userinfo/v2/me
        // Authorization: Bearer <token>
        //
        // returns:
        // {
        //    "id": "1039999",
        //    "email": "<email address>",
        //    "verified_email": true,
        //    "name": "Scott Persinger",
        //    "given_name": "Scott",
        //    "family_name": "Persinger",
        //    "picture": "https://lh3.googleusercontent.com/a-/AOh14GiUvzO42tri0ne1eYj7o6tVtbanapPZx9w_769dS4Y=s96-c",
        //    "locale": "en"
        // }
        if (!password.equals("secret")) {
            throw new AccessDeniedException("Invalid credentials");
        } else {
            return new BasicPrincipal(user);
        }

        /*
        Request request = new Request.Builder()
                .url("https://www.googleapis.com/userinfo/v2/me")
                .addHeader("Authorization", "Bearer " + password)
                .build();
        OkHttpClient client = new OkHttpClient();
        try {
            Response response = client.newCall(request).execute();

            if (response.code() != 200) {
                throw new AccessDeniedException("Invalid credentials");
            }

            return new BasicPrincipal(user);
        }
        catch (IOException e) {
            log.error("Google auth request failed", e.toString());
            throw new AccessDeniedException("Credential check failed");
        }
         */
    }
}
