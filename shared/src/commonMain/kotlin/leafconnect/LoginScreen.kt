package leafconnect

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import leafconnect.generated.resources.Res
import leafconnect.generated.resources.app_name
import leafconnect.generated.resources.auto_login
import leafconnect.generated.resources.email_invalid
import leafconnect.generated.resources.email_required
import leafconnect.generated.resources.hint_password
import leafconnect.generated.resources.hint_user
import leafconnect.generated.resources.ic_launcher
import leafconnect.generated.resources.ic_launcher_dark
import leafconnect.generated.resources.keep_credentials
import leafconnect.generated.resources.login
import leafconnect.generated.resources.password_required
import leafconnect.generated.resources.region_canada
import leafconnect.generated.resources.region_label
import leafconnect.generated.resources.region_us
import org.jetbrains.compose.resources.stringResource

/**
 * The login screen, laid out like the original `activity_login.xml`: the app
 * icon, the two credential fields (hints, no floating labels), the horizontal
 * region radios under a 16sp label, the fixed-width Log in button, then the
 * two checkbox rows. Validation is shown on the field itself — the original set
 * `EditText.error`, which is what `isError` + supporting text correspond to.
 *
 * Same rules as before: fields are prefilled from RAM/store; the region starts
 * from the stored choice (else the device locale) and is written the moment it
 * changes; both checkboxes persist on toggle, and unchecking Keep credentials
 * drops the stored email and password immediately.
 */
@Composable
fun LoginScreen(controller: AppController) {
    var email by remember { mutableStateOf(controller.initialEmail()) }
    var password by remember { mutableStateOf(controller.initialPassword()) }
    var keepCredentials by remember { mutableStateOf(controller.keepCredentialsStored()) }
    var autoLogin by remember { mutableStateOf(controller.autoLoginStored()) }
    var region by remember { mutableStateOf(controller.initialRegion()) }
    var emailError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    // Error texts are resolved during composition; the click handler below is
    // not a composable context.
    val emailRequiredText = stringResource(Res.string.email_required)
    val emailInvalidText = stringResource(Res.string.email_invalid)
    val passwordRequiredText = stringResource(Res.string.password_required)

    // The original ScrollView with fillViewport + gravity=center: centered
    // while everything fits, scrollable (landscape, small screens) when not.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = if (isSystemInDarkTheme()) {
                    painterResource(Res.drawable.ic_launcher_dark)
                } else {
                    painterResource(Res.drawable.ic_launcher)
                },
                contentDescription = stringResource(Res.string.app_name),
                modifier = Modifier.size(288.dp).padding(bottom = 8.dp),
            )

            OutlinedTextField(
                value = email,
                onValueChange = {
                    email = it
                    emailError = null
                },
                placeholder = { Text(stringResource(Res.string.hint_user), fontSize = 14.sp) },
                singleLine = true,
                isError = emailError != null,
                supportingText = emailError?.let { { Text(it, fontSize = 12.sp) } },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    passwordError = null
                },
                placeholder = { Text(stringResource(Res.string.hint_password), fontSize = 14.sp) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                isError = passwordError != null,
                supportingText = passwordError?.let { { Text(it, fontSize = 12.sp) } },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                stringResource(Res.string.region_label),
                fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                RegionOption(
                    label = stringResource(Res.string.region_canada),
                    selected = region == AppLocale.COUNTRY_CA,
                    onSelect = {
                        region = AppLocale.COUNTRY_CA
                        controller.selectRegion(AppLocale.COUNTRY_CA)
                    },
                )
                Spacer(Modifier.width(16.dp))
                RegionOption(
                    label = stringResource(Res.string.region_us),
                    selected = region == AppLocale.COUNTRY_US,
                    onSelect = {
                        region = AppLocale.COUNTRY_US
                        controller.selectRegion(AppLocale.COUNTRY_US)
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    // Validation mirrors the original: the message lands on the
                    // field, and the password is used EXACTLY as typed (spaces
                    // are part of it, so it is never trimmed).
                    val trimmed = email.trim()
                    if (trimmed.isEmpty()) {
                        emailError = emailRequiredText
                        return@Button
                    }
                    if (!controller.isValidEmail(trimmed)) {
                        emailError = emailInvalidText
                        return@Button
                    }
                    emailError = null
                    if (password.isEmpty()) {
                        passwordError = passwordRequiredText
                        return@Button
                    }
                    passwordError = null
                    controller.login(trimmed, password, keepCredentials)
                },
                modifier = Modifier.width(300.dp),
            ) {
                Text(stringResource(Res.string.login))
            }

            CheckboxRow(
                checked = keepCredentials,
                label = stringResource(Res.string.keep_credentials),
                topPadding = 8.dp,
                onToggle = {
                    keepCredentials = !keepCredentials
                    controller.setKeepCredentials(keepCredentials)
                },
            )
            CheckboxRow(
                checked = autoLogin,
                label = stringResource(Res.string.auto_login),
                topPadding = 0.dp,
                onToggle = {
                    autoLogin = !autoLogin
                    controller.setAutoLogin(autoLogin)
                },
            )
        }
    }
}

@Composable
private fun RegionOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, fontSize = 16.sp)
    }
}

/** Box + text, vertically centered; tapping the whole row toggles the box. */
@Composable
private fun CheckboxRow(
    checked: Boolean,
    label: String,
    topPadding: Dp,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding)
            .clickable(onClick = onToggle),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(label, fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp))
    }
}
