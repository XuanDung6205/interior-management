import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Helper for the isolated TV3 dev database. Never accepts a password argument. */
class HashDemoPassword {
    public static void main(String[] args) {
        String password=System.getenv("TV3_DEMO_PASSWORD");
        if(password==null || password.length()<10) throw new IllegalArgumentException("PASSWORD at least 10 characters.");
        System.out.print(new BCryptPasswordEncoder().encode(password));
    }
}
