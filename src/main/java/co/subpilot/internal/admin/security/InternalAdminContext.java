package co.subpilot.internal.admin.security;

/**
 * Thread-local holder for the current authenticated internal admin —
 * mirrors TenantContext's design exactly (co.subpilot.common.tenant),
 * but deliberately a SEPARATE holder, not a reused/extended one. Mixing
 * merchant and internal-admin identity into one thread-local would make
 * "merchant cookies must not authenticate internal routes, internal admin
 * cookies must not authenticate merchant routes" a matter of application
 * logic remembering to check the right field, instead of it being
 * structurally impossible.
 */
public final class InternalAdminContext {

    private static final InheritableThreadLocal<String> ADMIN_ID = new InheritableThreadLocal<>();
    private static final InheritableThreadLocal<String> ROLE = new InheritableThreadLocal<>();
    private static final InheritableThreadLocal<String> EMAIL = new InheritableThreadLocal<>();

    private InternalAdminContext() {}

    public static void set(String adminId, String role, String email) {
        ADMIN_ID.set(adminId);
        ROLE.set(role);
        EMAIL.set(email);
    }

    public static String getAdminId() { return ADMIN_ID.get(); }
    public static String getRole() { return ROLE.get(); }
    public static String getEmail() { return EMAIL.get(); }

    public static String requireAdminId() {
        String id = ADMIN_ID.get();
        if (id == null) {
            throw new IllegalStateException("No internal admin context set. Must only be called from authenticated internal admin requests.");
        }
        return id;
    }

    public static boolean isSuperAdmin() {
        return co.subpilot.internal.admin.InternalAdminRole.SUPER_ADMIN.equals(ROLE.get());
    }

    public static void clear() {
        ADMIN_ID.remove();
        ROLE.remove();
        EMAIL.remove();
    }
}