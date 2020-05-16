package me.syari.ss.discord.internal.managers;

import me.syari.ss.discord.api.AccountType;
import me.syari.ss.discord.api.entities.Icon;
import me.syari.ss.discord.api.entities.SelfUser;
import me.syari.ss.discord.api.managers.AccountManager;
import me.syari.ss.discord.api.requests.Request;
import me.syari.ss.discord.api.requests.Response;
import me.syari.ss.discord.api.utils.data.DataObject;
import me.syari.ss.discord.internal.requests.Route;
import me.syari.ss.discord.internal.utils.Checks;
import okhttp3.RequestBody;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;

public class AccountManagerImpl extends ManagerBase<AccountManager> implements AccountManager {
    protected final SelfUser selfUser;

    protected String currentPassword;

    protected String name;
    protected Icon avatar;
    protected String email;
    protected String password;


    public AccountManagerImpl(SelfUser selfUser) {
        super(selfUser.getJDA(), Route.Self.MODIFY_SELF.compile());
        this.selfUser = selfUser;
    }

    @Nonnull
    @Override
    public SelfUser getSelfUser() {
        return selfUser;
    }

    @Nonnull
    @Override
    @CheckReturnValue
    public AccountManagerImpl reset(long fields) {
        super.reset(fields);
        if ((fields & AVATAR) == AVATAR)
            avatar = null;
        return this;
    }

    @Nonnull
    @Override
    @CheckReturnValue
    public AccountManagerImpl reset(long... fields) {
        super.reset(fields);
        return this;
    }

    @Nonnull
    @Override
    @CheckReturnValue
    public AccountManagerImpl reset() {
        super.reset();
        avatar = null;
        return this;
    }

    @Nonnull
    @Override
    @CheckReturnValue
    public AccountManagerImpl setName(@Nonnull String name, String currentPassword) {
        Checks.notBlank(name, "Name");
        Checks.check(name.length() >= 2 && name.length() <= 32, "Name must be between 2-32 characters long");
        this.currentPassword = currentPassword;
        this.name = name;
        set |= NAME;
        return this;
    }

    @Override
    protected RequestBody finalizeData() {
        boolean isClient = getJDA().getAccountType() == AccountType.CLIENT;
        Checks.check(!isClient || (currentPassword != null && !currentPassword.isEmpty()),
                "Provided client account password to be used in auth is null or empty!");

        DataObject body = DataObject.empty();

        //Required fields. Populate with current values..
        body.put("username", getSelfUser().getName());
        body.put("avatar", getSelfUser().getAvatarId());

        if (shouldUpdate(NAME))
            body.put("username", name);
        if (shouldUpdate(AVATAR))
            body.put("avatar", avatar == null ? null : avatar.getEncoding());

        if (isClient) {
            //Required fields. Populate with current values.
            body.put("password", currentPassword);
            body.put("email", email);

            if (shouldUpdate(EMAIL))
                body.put("email", email);
            if (shouldUpdate(PASSWORD))
                body.put("new_password", password);
        }

        reset();
        return getRequestBody(body);
    }

    @Override
    protected void handleSuccess(Response response, Request<Void> request) {
        String newToken = response.getObject().getString("token").replace("Bot ", "");
        api.setToken(newToken);
        request.onSuccess(null);
    }
}
