import { ReactNode } from "react";
import { redirect } from "next/navigation";
import { getSession } from "@/lib/session";

const Layout = async ({ children }: { children: ReactNode }) => {
  const session = await getSession();

  if (session) {
    redirect("/");
  }

  return (
    <main className="grid min-h-screen lg:grid-cols-2">
      <section className="flex items-center justify-center bg-white px-6 py-12">
        <div className="mx-auto w-full max-w-sm">
          <img
            src="/images/mundia-logo.png"
            alt="Mundiapolis"
            width={200}
            height={67}
            className="mb-10 h-auto w-[180px] sm:w-[200px]"
          />
          {children}
        </div>
      </section>

      <section className="relative hidden lg:block">
        <img
          src="/images/mundiapolis-campus-optimized.jpg"
          alt="Université Mundiapolis campus"
          className="size-full object-cover"
          style={{ objectPosition: "74% center" }}
        />
        <div className="absolute inset-0 bg-gradient-to-t from-black/60 via-transparent to-transparent" />
        <div className="absolute bottom-8 left-8 right-8 z-10">
          <p className="text-lg font-serif text-white sm:text-2xl">Mundiatheque</p>
          <p className="mt-1 text-sm text-white/80">Ouvrez un livre, ouvrez votre esprit.</p>
        </div>
      </section>
    </main>
  );
};

export default Layout;
