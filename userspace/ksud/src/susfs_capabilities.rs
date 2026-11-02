//! Pure SUSFS probe result handling shared by production and host tests.

pub fn probe_result(syscall_result: i64, command_error: i32) -> Result<(), String> {
    if syscall_result < 0 {
        return Err(format!("SUSFS syscall failed: return={syscall_result}"));
    }
    if command_error != 0 {
        return Err(format!("SUSFS command returned err={command_error}"));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::probe_result;

    #[test]
    fn accepts_zero_return_and_zero_command_error() {
        assert!(probe_result(0, 0).is_ok());
    }

    #[test]
    fn rejects_outer_failure() {
        assert!(probe_result(-22, 0).is_err());
    }

    #[test]
    fn rejects_command_error_even_when_outer_call_succeeds() {
        assert!(probe_result(0, 126).is_err());
    }
}
